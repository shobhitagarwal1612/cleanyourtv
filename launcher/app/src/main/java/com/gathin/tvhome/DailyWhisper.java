package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calm, auto-refreshing daily context for the Home screen's open editorial slot under the clock:
 * - High-signal local weather/AQI advisories (via the weather provider, when active)
 * - Upcoming festivals, public holidays and astronomy nights (100% offline, 0–7 days ahead)
 * - Optional single distilled daily headline (can be toggled off in Launcher settings)
 * - Time-of-day literary & philosophical thought of the day (100% offline anthology)
 */
final class DailyWhisper {
    static final int TURN_SECONDS = 24;
    private static final long REFRESH_GAP_MS = 10 * 60_000L;
    private static final long RESUME_GAP_MS = 60_000L;

    static final int COLOUR_FESTIVAL = 0xFFF5B041;
    static final int COLOUR_SKY = 0xFF34D399;
    static final int COLOUR_DAY_QUOTE = 0xFFA78BFA;
    static final int COLOUR_NIGHT_QUOTE = 0xFF38BDF8;

    static final class Item {
        final String kicker;
        final String headline;
        final String subline;
        final int colour;

        Item(String kicker, String headline, String subline, int colour) {
            this.kicker = kicker;
            this.headline = headline;
            this.subline = subline;
            this.colour = colour;
        }
    }

    private final Context app;
    private final String providerPkg;
    private final String mode;
    private final Handler main;
    private final Runnable onUpdate;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Item> connectedItems = Collections.emptyList();
    private long lastAskedElapsed = -REFRESH_GAP_MS;
    private boolean busy;

    DailyWhisper(Context context, String providerPkg, String mode, Handler main, Runnable onUpdate) {
        this.app = context.getApplicationContext();
        this.providerPkg = providerPkg == null ? "" : providerPkg.trim();
        this.mode = mode == null ? Config.WHISPER_DEFAULT : mode;
        this.main = main;
        this.onUpdate = onUpdate;
    }

    boolean isEnabled() {
        return !Config.WHISPER_OFF.equals(mode);
    }

    void onResume() {
        if (!isEnabled()) return;
        askProvider(RESUME_GAP_MS);
    }

    void onTick() {
        if (!isEnabled()) return;
        askProvider(REFRESH_GAP_MS);
    }

    void shutdown() {
        io.shutdownNow();
    }

    private void askProvider(long minGapMs) {
        if (Config.WHISPER_OFFLINE.equals(mode) || Config.WHISPER_OFF.equals(mode) || providerPkg.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (busy || now - lastAskedElapsed < minGapMs) return;
        lastAskedElapsed = now;
        busy = true;
        final boolean includeNews = Config.WHISPER_ALL.equals(mode);
        try {
            io.execute(() -> {
                List<Item> fetched = readProvider(app, providerPkg, includeNews);
                connectedItems = fetched;
                main.post(() -> {
                    busy = false;
                    if (onUpdate != null) onUpdate.run();
                });
            });
        } catch (RuntimeException ignored) {
            busy = false;
        }
    }

    private static List<Item> readProvider(Context app, String pkg, boolean includeNews) {
        String authority = pkg + ".now";
        List<Item> out = new ArrayList<>(2);
        try {
            ProviderInfo info = app.getPackageManager().resolveContentProvider(authority, 0);
            if (info == null || !pkg.equals(info.packageName)) return out;
            try (Cursor c = app.getContentResolver().query(
                    Uri.parse("content://" + authority + "/whisper"),
                    null, null, null, null)) {
                if (c == null) return out;
                while (c.moveToNext()) {
                    String kind = c.getString(0);
                    if ("news".equals(kind) && !includeNews) continue;
                    String kicker = safe(c.getString(1), 44);
                    String headline = safe(c.getString(2), 62);
                    String subline = safe(c.getString(3), 58);
                    int colour = c.getInt(4);
                    if (!headline.isEmpty()) {
                        out.add(new Item(kicker, headline, subline, colour));
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    /** Builds the ordered list of active turns for right now (at most 3–4 calm turns). */
    List<Item> itemsNow() {
        if (!isEnabled()) return Collections.emptyList();
        List<Item> result = new ArrayList<>(4);
        List<Item> net = connectedItems;

        // 1. Severe Weather / Rain / AQI Alert takes first slot if active
        for (Item item : net) {
            if (item.colour == 0xFFEF4444 || item.colour == 0xFFF59E0B) {
                result.add(item);
            }
        }

        // 2. Upcoming Festival / Holiday / Sky event within 7 days
        LocalDate today = LocalDate.now();
        Item fest = upcomingFestivalOrSky(today);
        if (fest != null) {
            result.add(fest);
        }

        // 3. Single Distilled Top News Headline (when mode == WHISPER_ALL)
        if (Config.WHISPER_ALL.equals(mode)) {
            for (Item item : net) {
                if (item.colour != 0xFFEF4444 && item.colour != 0xFFF59E0B) {
                    result.add(item);
                }
            }
        }

        // 4. Time-of-Day Literary / Philosophical Thought of the Day
        result.add(quoteFor(today, LocalTime.now().getHour()));
        return result;
    }

    // ---- Offline Festival, Holiday & Astronomy Horizon (2025–2028) -------------------------------

    private static final class CalendarEvent {
        final int year;
        final int month;
        final int day;
        final String name;
        final String todayTitle;
        final String subtitle;
        final int colour;

        CalendarEvent(int year, int month, int day, String name, String todayTitle, String subtitle, int colour) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.name = name;
            this.todayTitle = todayTitle;
            this.subtitle = subtitle;
            this.colour = colour;
        }
    }

    // Year=0 means recurring every year on that month/day.
    private static final CalendarEvent[] EVENTS = {
        // Annual fixed holidays & sky events
        new CalendarEvent(0, 1, 1, "New Year's Day", "Happy New Year", "First day of the year · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(0, 1, 14, "Makar Sankranti", "Happy Makar Sankranti", "Harvest festival · Uttarayana begins", COLOUR_FESTIVAL),
        new CalendarEvent(0, 1, 26, "Republic Day", "Republic Day · India", "National holiday · 26 January", COLOUR_FESTIVAL),
        new CalendarEvent(0, 5, 1, "May Day", "May Day", "Public holiday · 1 May", COLOUR_FESTIVAL),
        new CalendarEvent(0, 8, 12, "Perseid Meteor Shower", "Perseid Meteors Peak Tonight", "Up to 60 meteors/hr · Look NE after midnight", COLOUR_SKY),
        new CalendarEvent(0, 8, 15, "Independence Day", "Independence Day · India", "National holiday · 15 August", COLOUR_FESTIVAL),
        new CalendarEvent(0, 10, 2, "Gandhi Jayanti", "Gandhi Jayanti", "National holiday · 2 October", COLOUR_FESTIVAL),
        new CalendarEvent(0, 12, 14, "Geminid Meteor Shower", "Geminid Meteors Peak Tonight", "Brightest meteor shower of the year · After 10 PM", COLOUR_SKY),
        new CalendarEvent(0, 12, 25, "Christmas", "Merry Christmas", "Public holiday · 25 December", COLOUR_FESTIVAL),

        // 2026 Lunar / Solar festivals
        new CalendarEvent(2026, 2, 15, "Maha Shivaratri", "Maha Shivaratri", "Festival of Shiva · Night vigil", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 3, 4, "Holi", "Happy Holi · Festival of Colours", "Spring festival · 4 March", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 3, 19, "Ugadi & Gudi Padwa", "Happy New Year · Ugadi", "Lunisolar New Year · 19 March", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 3, 20, "Eid al-Fitr", "Eid Mubarak", "End of Ramadan · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 8, 28, "Raksha Bandhan", "Happy Raksha Bandhan", "Shravana Purnima · 28 August", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 9, 4, "Krishna Janmashtami", "Happy Janmashtami", "Celebration of Krishna · 4 September", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 9, 14, "Ganesh Chaturthi", "Happy Ganesh Chaturthi", "Vinayaka Chaturthi · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 10, 20, "Maha Navami & Ayudha Pooja", "Happy Navaratri · Ayudha Pooja", "Autumn festival · 20 October", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 10, 21, "Vijayadashami · Dussehra", "Happy Vijayadashami", "Triumph of light · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 11, 6, "Dhanteras", "Happy Dhanteras", "Deepavali festivities begin · Friday", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 11, 8, "Deepavali · Diwali", "Happy Deepavali · Festival of Lights", "Festival of Lights · Sunday, 8 November", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 11, 9, "Govardhan Puja & Balipadyami", "Happy Deepavali", "Deepavali holiday · Monday, 9 November", COLOUR_FESTIVAL),
        new CalendarEvent(2026, 11, 24, "Guru Nanak Jayanti", "Happy Gurpurab", "Birth of Guru Nanak · 24 November", COLOUR_FESTIVAL),

        // 2027 Lunar / Solar festivals
        new CalendarEvent(2027, 3, 6, "Maha Shivaratri", "Maha Shivaratri", "Festival of Shiva · 6 March", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 3, 10, "Eid al-Fitr", "Eid Mubarak", "End of Ramadan · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 3, 22, "Holi", "Happy Holi · Festival of Colours", "Spring festival · 22 March", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 4, 7, "Ugadi & Gudi Padwa", "Happy New Year · Ugadi", "Lunisolar New Year · 7 April", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 9, 4, "Ganesh Chaturthi", "Happy Ganesh Chaturthi", "Vinayaka Chaturthi · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 10, 9, "Maha Navami & Ayudha Pooja", "Happy Navaratri · Ayudha Pooja", "Autumn festival · 9 October", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 10, 10, "Vijayadashami · Dussehra", "Happy Vijayadashami", "Triumph of light · Public holiday", COLOUR_FESTIVAL),
        new CalendarEvent(2027, 10, 29, "Deepavali · Diwali", "Happy Deepavali · Festival of Lights", "Festival of Lights · 29 October", COLOUR_FESTIVAL)
    };

    private static Item upcomingFestivalOrSky(LocalDate today) {
        CalendarEvent best = null;
        LocalDate bestDate = null;
        long bestDays = Long.MAX_VALUE;

        for (CalendarEvent ev : EVENTS) {
            if (ev.year != 0 && ev.year != today.getYear() && ev.year != today.getYear() + 1) continue;
            int y = ev.year == 0 ? today.getYear() : ev.year;
            LocalDate d = LocalDate.of(y, ev.month, ev.day);
            if (ev.year == 0 && d.isBefore(today)) {
                d = LocalDate.of(today.getYear() + 1, ev.month, ev.day);
            }
            long days = ChronoUnit.DAYS.between(today, d);
            if (days >= 0 && days <= 7 && days < bestDays) {
                bestDays = days;
                best = ev;
                bestDate = d;
            }
        }
        if (best == null || bestDate == null) return null;
        String kind = best.colour == COLOUR_SKY ? "TONIGHT'S SKY" : "FESTIVAL & HOLIDAY";
        if (bestDays == 0) {
            return new Item("TODAY · " + kind, best.todayTitle, best.subtitle, best.colour);
        }
        String dow = bestDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US).toUpperCase(Locale.US);
        String kicker = (bestDays == 1 ? "TOMORROW · " : "UPCOMING · ") + dow;
        String headline = best.name + (bestDays == 1 ? " · Tomorrow" : " · In " + bestDays + " Days");
        return new Item(kicker, headline, best.subtitle, best.colour);
    }

    // ---- Curated Offline Thought / Quote of the Day ----------------------------------------------

    private static final String[][] DAY_QUOTES = {
        {"MARCUS AURELIUS", "The impediment to action advances action.", "Meditations · Book V"},
        {"SENECA", "We suffer more in imagination than in reality.", "Letters from a Stoic"},
        {"RABINDRANATH TAGORE", "Faith is the bird that feels the light at dawn.", "Stray Birds"},
        {"CARL SAGAN", "Somewhere, something incredible is waiting to be known.", "Cosmos"},
        {"LAO TZU", "Nature does not hurry, yet everything is accomplished.", "Tao Te Ching"},
        {"HENRY DAVID THOREAU", "Only that day dawns to which we are awake.", "Walden"},
        {"ALBERT CAMUS", "In the midst of winter, I found an invincible summer.", "Return to Tipasa"},
        {"EPICTETUS", "First say to yourself what you would be, then do it.", "Discourses"},
        {"LEONARDO DA VINCI", "Simplicity is the ultimate sophistication.", "Notebooks"},
        {"VIKTOR FRANKL", "Between stimulus and response there is a space.", "Man's Search for Meaning"},
        {"HERACLITUS", "No man ever steps in the same river twice.", "Fragments"},
        {"ARISTOTLE", "Quality is not an act, it is a habit.", "Nicomachean Ethics"},
        {"KALIDASA", "Look to this day, for it is the very life of life.", "Salutation to the Dawn"},
        {"RALPH WALDO EMERSON", "Adopt the pace of nature: her secret is patience.", "Essays"},
        {"RICHARD FEYNMAN", "What I cannot create, I do not understand.", "Last Blackboard"},
        {"MIYAMOTO MUSASHI", "Do nothing that is of no use.", "The Book of Five Rings"},
        {"ANTOINE DE SAINT-EXUPÉRY", "Perfection is achieved when nothing is left to take away.", "Wind, Sand and Stars"},
        {"SWAMI VIVEKANANDA", "Arise, awake, and stop not till the goal is reached.", "Katha Upanishad"},
        {"MARIE CURIE", "Nothing in life is to be feared, it is only to be understood.", "Notes"},
        {"JIDDU KRISHNAMURTI", "The ability to observe without evaluating is intelligence.", "Freedom from the Known"},
        {"MONTESQUIEU", "To become truly great, one has to stand with people.", "Pensées"},
        {"CHARLES EAMES", "The details are not the details. They make the design.", "Design Notes"},
        {"DIETER RAMS", "Good design is as little design as possible.", "Ten Principles"},
        {"ARTHUR C. CLARKE", "Any sufficiently advanced technology is like magic.", "Profiles of the Future"}
    };

    private static final String[][] NIGHT_QUOTES = {
        {"RAINER MARIA RILKE", "Let everything happen to you: beauty and terror.", "Book of Hours"},
        {"KAHLIL GIBRAN", "Out of suffering have emerged the strongest souls.", "The Prophet"},
        {"JORGE LUIS BORGES", "Time is the substance I am made of.", "Other Inquisitions"},
        {"RUMI", "Silence is the language of God, all else is poor translation.", "Masnavi"},
        {"FERNANDO PESSOA", "Literature is the most agreeable way of ignoring life.", "The Book of Disquiet"},
        {"MATSUO BASHO", "Every day is a journey, and the journey itself is home.", "Narrow Road to the Deep North"},
        {"ITALO CALVINO", "Take life lightly, for lightness is not superficiality.", "Six Memos"},
        {"HARUKI MURAKAMI", "Pain is inevitable. Suffering is optional.", "What I Talk About When I Talk About Running"},
        {"PABLO NERUDA", "I want to do with you what spring does with cherry trees.", "Twenty Love Poems"},
        {"HERMANN HESSE", "Within you, there is a stillness and a sanctuary.", "Siddhartha"},
        {"MARCEL PROUST", "The real voyage of discovery consists in having new eyes.", "In Search of Lost Time"},
        {"VIRGINIA WOOLF", "Arrange whatever pieces come your way.", "A Writer's Diary"},
        {"OCTAVIO PAZ", "Wisdom lies neither in fixity nor in change, but in the dialectic.", "The Labyrinth of Solitude"},
        {"WALT WHITMAN", "Keep your face always toward the sunshine.", "Leaves of Grass"},
        {"MARY OLIVER", "Tell me, what is it you plan to do with your one wild life?", "The Summer Day"},
        {"SEAMUS HEANEY", "Walk on air against your better judgement.", "The Gravel Walks"},
        {"T. S. ELIOT", "Every moment is a fresh beginning.", "Four Quartets"},
        {"ALAN WATTS", "Muddy water is best cleared by leaving it alone.", "The Way of Zen"},
        {"URSULA K. LE GUIN", "To light a candle is to cast a shadow.", "A Wizard of Earthsea"},
        {"SOPHOCLES", "Look and you will find it—what is unsought will go undetected.", "Fragments"}
    };

    private static Item quoteFor(LocalDate today, int hour) {
        boolean night = hour >= 19 || hour < 5;
        String[][] pool = night ? NIGHT_QUOTES : DAY_QUOTES;
        int seed = today.getDayOfYear() + today.getYear() * 37;
        String[] entry = pool[Math.floorMod(seed, pool.length)];
        String kicker = (night ? "EVENING NOTE · " : "DAILY THOUGHT · ") + entry[0];
        String headline = "\u201C" + entry[1] + "\u201D";
        int colour = night ? COLOUR_NIGHT_QUOTE : COLOUR_DAY_QUOTE;
        return new Item(kicker, headline, entry[2], colour);
    }
}
