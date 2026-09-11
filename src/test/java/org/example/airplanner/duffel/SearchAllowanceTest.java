package org.example.airplanner.duffel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class SearchAllowanceTest {
    @TempDir Path directory;
    private SearchAllowance allowance(String date, int daily, int monthly) {
        return new SearchAllowance(directory.resolve("usage.json"), daily, monthly,
                Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test void dailyLimitSurvivesRestart() {
        allowance("2026-09-10", 1, 5).reserve();
        var restarted = allowance("2026-09-10", 1, 5);
        assertEquals(1, restarted.read().dailyUsed());
        assertEquals(429, assertThrows(DuffelException.class, restarted::reserve).status());
    }

    @Test void dailyResetKeepsMonthlyLimitAndMonthResetClearsBoth() {
        allowance("2026-09-10", 2, 2).reserve();
        var nextDay = allowance("2026-09-11", 2, 2);
        assertEquals(0, nextDay.read().dailyUsed());
        assertEquals(2, nextDay.reserve().monthlyUsed());
        assertThrows(DuffelException.class, nextDay::reserve);
        assertEquals(1, allowance("2026-10-01", 2, 2).reserve().monthlyUsed());
    }

    @Test void corruptFileBlocksSearchInsteadOfResettingAllowance() throws Exception {
        Files.writeString(directory.resolve("usage.json"), "broken");
        assertEquals(503, assertThrows(DuffelException.class,
                () -> allowance("2026-09-10", 2, 2).reserve()).status());
        assertEquals("broken", Files.readString(directory.resolve("usage.json")));
    }

    @Test void concurrentReservationsCannotExceedLimit() throws Exception {
        var budget = allowance("2026-09-10", 3, 3);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) tasks.add(pool.submit(() -> {
                try { budget.reserve(); return true; } catch (DuffelException e) { return false; }
            }));
            int accepted = 0;
            for (var task : tasks) if (task.get()) accepted++;
            assertEquals(3, accepted);
            assertEquals(3, budget.read().monthlyUsed());
        }
    }
}
