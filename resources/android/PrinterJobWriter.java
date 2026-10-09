package com.nembestil.pos3.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.List;

/** Owns paper boundaries for all direct printers. No transport may start the
 * next document until this writer has sent its feed/CUT and recovery pause. */
final class PrinterJobWriter {
    private static final int CHUNK_BYTES = 2_048;
    private static final long BLUETOOTH_CHUNK_PAUSE_MS = 20;
    private static final long CUT_RECOVERY_PAUSE_MS = 1_000;
    private static final long STATUS_TIMEOUT_MS = 30_000;
    private static final long STATUS_POLL_MS = 10;

    static final class Document {
        final byte[] bytes;
        final String cut;

        Document(byte[] bytes, String cut) {
            if (cut != null && !"full".equals(cut) && !"partial".equals(cut)) {
                throw new IllegalArgumentException("Unsupported printer cut mode.");
            }
            this.bytes = bytes;
            this.cut = cut;
        }
    }

    final String language;
    final int feedLines;
    final List<Document> documents;

    PrinterJobWriter(String language, int feedLines, List<Document> documents) {
        if (!"esc-pos".equals(language) && !"star-prnt".equals(language) && !"star-line".equals(language)) {
            throw new IllegalArgumentException("Unsupported printer command language.");
        }
        if (feedLines < 0 || feedLines > 255 || documents.isEmpty()) {
            throw new IllegalArgumentException("Invalid printer document framing.");
        }
        this.language = language;
        this.feedLines = feedLines;
        this.documents = documents;
    }

    int byteCount() {
        int count = 0;
        for (Document document : documents) count += document.bytes.length;
        return count;
    }

    void writeTo(OutputStream output, boolean bluetooth, InputStream printerStatus) throws IOException {
        boolean awaitCompletion = "esc-pos".equals(language)
            && documents.stream().anyMatch(document -> document.cut != null);
        if (awaitCompletion) {
            if (printerStatus == null) throw new IOException("The ESC/POS printer has no readable status channel.");
            // Verify bidirectional status before printing any paper. Disable
            // unsolicited ASB so it cannot be mistaken for our queued reply.
            while (printerStatus.available() > 0) {
                checkInterrupted();
                if (printerStatus.read() < 0) throw new IOException("The printer disconnected.");
            }
            output.write(new byte[] {0x1d, 0x61, 0x00});
            awaitPrinted(output, printerStatus);
        }
        for (Document document : documents) {
            for (int offset = 0; offset < document.bytes.length; offset += CHUNK_BYTES) {
                checkInterrupted();
                output.write(document.bytes, offset, Math.min(CHUNK_BYTES, document.bytes.length - offset));
                if (bluetooth) {
                    output.flush();
                    pause(BLUETOOTH_CHUNK_PAUSE_MS);
                }
            }
            output.flush();
            if (document.cut == null) continue; // Cash-drawer pulse: no paper/cut.

            // Bluetooth flush only hands bytes to Android. GS r is queued by
            // the printer after the preceding print data; DLE EOT is real-time
            // and cannot establish this boundary.
            if (awaitCompletion) awaitPrinted(output, printerStatus);
            checkInterrupted();
            // Send the cutter separately, on the same connection, after every
            // body byte. Reset line spacing after column graphics; never reset
            // the print buffer before feeding/cutting its remaining content.
            int mode = "partial".equals(document.cut) ? 1 : 0;
            if ("esc-pos".equals(language)) {
                output.write(new byte[] {0x1b, 0x32, 0x1b, 0x64, (byte) feedLines, 0x1d, 0x56, (byte) mode});
            } else {
                output.write(new byte[] {0x1b, 0x7a, 0x01});
                for (int line = 0; line < feedLines; line++) output.write(new byte[] {0x0a, 0x0d});
                output.write(new byte[] {0x1b, 0x64, (byte) mode});
            }
            output.flush();
            if (awaitCompletion) awaitPrinted(output, printerStatus);
            // Keep the connection and printer turn until this pause finishes,
            // including between multiple pages/copies in a single server job.
            pause(CUT_RECOVERY_PAUSE_MS);
        }
    }

    private static void awaitPrinted(OutputStream output, InputStream input) throws IOException {
        output.write(new byte[] {0x1d, 0x72, 0x01}); // Queued paper status: GS r 1.
        output.flush();
        long deadline = System.nanoTime() + STATUS_TIMEOUT_MS * 1_000_000;
        int automaticStatusBytes = 0;
        while (System.nanoTime() < deadline) {
            checkInterrupted();
            if (input.available() == 0) {
                pause(STATUS_POLL_MS);
                continue;
            }
            int status = input.read();
            if (status < 0) throw new IOException("The printer disconnected before acknowledging the print.");
            if (automaticStatusBytes > 0) {
                automaticStatusBytes--;
                continue;
            }
            if ((status & 0x93) == 0x10) {
                automaticStatusBytes = 3; // ASB has four bytes; discard the entire reply.
                continue;
            }
            if ((status & 0x90) != 0) continue; // Real-time/power-on replies are not completion.
            if ((status & 0x0c) != 0) throw new IOException("The printer is out of paper.");
            return;
        }
        // Never report success or send the next document after an unconfirmed
        // body. A guessed delay would recreate the overlapping-job problem.
        throw new SocketTimeoutException("The printer did not acknowledge print completion within 30 seconds.");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("The printer write was interrupted.");
    }

    private static void pause(long milliseconds) throws InterruptedIOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The printer write was interrupted.");
        }
    }
}
