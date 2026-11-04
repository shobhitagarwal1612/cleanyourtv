package com.lionlev.tv.capacity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Reads KiB counters once; old kernels lack MemAvailable, so use a conservative estimate. */
final class MemoryCapacity {
    private MemoryCapacity() {
    }

    static long[] readMb(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        long total = -1, available = -1, free = -1, buffers = -1, cached = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            int kind;
            if (line.regionMatches(0, "MemTotal:", 0, 9) && colon == 8) kind = 0;
            else if (line.regionMatches(0, "MemAvailable:", 0, 13) && colon == 12) kind = 1;
            else if (line.regionMatches(0, "MemFree:", 0, 8) && colon == 7) kind = 2;
            else if (line.regionMatches(0, "Buffers:", 0, 8) && colon == 7) kind = 3;
            else if (line.regionMatches(0, "Cached:", 0, 7) && colon == 6) kind = 4;
            else continue;

            long value = parseKb(line, colon + 1);
            if (value < 0) continue;
            switch (kind) {
                case 0: total = value; break;
                case 1: available = value; break;
                case 2: free = value; break;
                case 3: buffers = value; break;
                case 4: cached = value; break;
            }
        }
        if (available < 0 && free >= 0 && buffers >= 0 && cached >= 0) {
            try { available = Math.addExact(Math.addExact(free, buffers), cached); }
            catch (ArithmeticException ignored) { available = -1; }
        }
        if (available >= 0 && total > 0) available = Math.min(available, total);
        return new long[]{available < 0 ? -1 : available / 1024, total < 1024 ? -1 : total / 1024};
    }

    /**
     * Returns {totalKb, availableKb, cachePlusBuffersKb, swapTotalKb, swapFreeKb} in a single pass.
     */
    static long[] readDetailedKb(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        long total = -1, available = -1, free = -1, buffers = -1, cached = -1, swapTotal = -1, swapFree = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            int kind;
            if (line.regionMatches(0, "MemTotal:", 0, 9) && colon == 8) kind = 0;
            else if (line.regionMatches(0, "MemAvailable:", 0, 13) && colon == 12) kind = 1;
            else if (line.regionMatches(0, "MemFree:", 0, 8) && colon == 7) kind = 2;
            else if (line.regionMatches(0, "Buffers:", 0, 8) && colon == 7) kind = 3;
            else if (line.regionMatches(0, "Cached:", 0, 7) && colon == 6) kind = 4;
            else if (line.regionMatches(0, "SwapTotal:", 0, 10) && colon == 9) kind = 5;
            else if (line.regionMatches(0, "SwapFree:", 0, 9) && colon == 8) kind = 6;
            else continue;

            long value = parseKb(line, colon + 1);
            if (value < 0) continue;
            switch (kind) {
                case 0: total = value; break;
                case 1: available = value; break;
                case 2: free = value; break;
                case 3: buffers = value; break;
                case 4: cached = value; break;
                case 5: swapTotal = value; break;
                case 6: swapFree = value; break;
            }
        }
        if (available < 0 && free >= 0 && buffers >= 0 && cached >= 0) {
            try { available = Math.addExact(Math.addExact(free, buffers), cached); }
            catch (ArithmeticException ignored) { available = -1; }
        }
        if (available >= 0 && total > 0) available = Math.min(available, total);
        long cacheKb = (buffers >= 0 ? buffers : 0) + (cached >= 0 ? cached : 0);
        return new long[]{total, available, cacheKb, swapTotal, swapFree};
    }

    private static long parseKb(String line, int start) {
        int len = line.length();
        int i = start;
        while (i < len && line.charAt(i) <= ' ') i++;
        int numStart = i;
        while (i < len && line.charAt(i) > ' ') i++;
        int numEnd = i;
        if (numStart >= numEnd) return -1;
        while (i < len && line.charAt(i) <= ' ') i++;
        int unitStart = i;
        while (i < len && line.charAt(i) > ' ') i++;
        int unitEnd = i;
        if (unitEnd - unitStart != 2 || line.charAt(unitStart) != 'k' || line.charAt(unitStart + 1) != 'B') {
            return -1;
        }
        try {
            return Long.parseLong(line.substring(numStart, numEnd));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}


