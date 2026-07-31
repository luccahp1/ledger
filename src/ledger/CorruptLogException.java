package ledger;

import java.io.IOException;

/**
 * Thrown when the log is damaged in a way recovery cannot silently repair.
 *
 * <p>This is deliberately narrow. Damage at the <em>tail</em> of the log is expected, since it is
 * what a crash mid-append looks like, and recovery handles it by truncating rather than throwing.
 * This exception is for the cases that should be impossible: a record the in-memory index points
 * at that cannot be read back.
 */
public class CorruptLogException extends IOException {

    private static final long serialVersionUID = 1L;

    public CorruptLogException(String message) {
        super(message);
    }
}
