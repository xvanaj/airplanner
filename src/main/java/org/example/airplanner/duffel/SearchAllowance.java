package org.example.airplanner.duffel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import static java.nio.file.StandardOpenOption.*;

/** Reserve before HTTP; ambiguous failures still count. Limits use UTC and survive restarts. */
public class SearchAllowance {
    private final Path file;
    private final int dailyLimit;
    private final int monthlyLimit;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchAllowance(Path file, int dailyLimit, int monthlyLimit, Clock clock) {
        this.file = file.toAbsolutePath().normalize();
        this.dailyLimit = dailyLimit;
        this.monthlyLimit = monthlyLimit;
        this.clock = clock;
    }

    public synchronized Usage read() { return access(false); }
    public synchronized Usage reserve() { return access(true); }

    private Usage access(boolean reserve) {
        try {
            Files.createDirectories(file.getParent());
            // Separate lock file stays locked throughout read + atomic replacement.
            try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), CREATE, WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new DuffelException(429, "Another process is updating the Duffel allowance. Try later.");
                LocalDate today = LocalDate.now(clock);
                String month = YearMonth.from(today).toString();
                boolean existing = Files.exists(file);
                Usage old = existing ? mapper.readValue(Files.readString(file), Usage.class) : null;
                if (existing && old == null) throw new IOException("Invalid usage file");
                if (old != null && (old.day() == null || old.month() == null || old.dailyUsed() < 0 || old.monthlyUsed() < old.dailyUsed())) {
                    throw new IOException("Invalid usage file");
                }
                if (old != null) {
                    LocalDate oldDay = LocalDate.parse(old.day());
                    if (!YearMonth.from(oldDay).toString().equals(old.month()) || oldDay.isAfter(today)) {
                        throw new IOException("Invalid usage period");
                    }
                }
                int daily = old != null && today.toString().equals(old.day()) ? old.dailyUsed() : 0;
                int monthly = old != null && month.equals(old.month()) ? old.monthlyUsed() : 0;
                if (reserve && (daily >= dailyLimit || monthly >= monthlyLimit)) {
                    throw new DuffelException(429, "Local Duffel search allowance reached. Wait for the next UTC day or month.");
                }
                Usage usage = new Usage(today.toString(), month, daily + (reserve ? 1 : 0),
                        monthly + (reserve ? 1 : 0), dailyLimit, monthlyLimit);
                if (reserve) {
                    Path temporary = Files.createTempFile(file.getParent(), "duffel-usage-", ".tmp");
                    try {
                        byte[] bytes = mapper.writeValueAsBytes(usage);
                        try (FileChannel output = FileChannel.open(temporary, WRITE)) {
                            ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            while (buffer.hasRemaining()) output.write(buffer);
                            output.force(true);
                        }
                        Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
                return usage;
            }
        } catch (OverlappingFileLockException e) {
            throw new DuffelException(429, "Another process is updating the Duffel allowance. Try later.");
        } catch (IOException | SecurityException | java.time.DateTimeException e) {
            throw new DuffelException(503, "Cannot safely read or save the Duffel allowance. No search was sent.");
        }
    }

    public record Usage(String day, String month, int dailyUsed, int monthlyUsed, int dailyLimit, int monthlyLimit) { }
}
