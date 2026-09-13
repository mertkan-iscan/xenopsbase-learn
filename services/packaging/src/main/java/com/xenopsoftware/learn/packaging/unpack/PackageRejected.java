package com.xenopsoftware.learn.packaging.unpack;

/**
 * The archive was refused, and this is the sentence the author reads (T-4.1).
 *
 * <p><b>Its own exception because the ANSWER is different, not because the message is.</b> A
 * rejection means the upload will never work as it stands and the person who exported it has
 * something to change; anything else that goes wrong here — storage unreachable, a read timing
 * out — means try again later and tells the author nothing. The two land in different states
 * ({@code REJECTED} and {@code FAILED}) precisely so a support queue does not fill up with
 * "it says it failed".
 *
 * <p>Messages name the entry. "Rejected: contains a path outside the package" is a sentence
 * nobody can act on; naming {@code ../../etc/passwd} tells an honest author which of their four
 * hundred files an authoring tool wrote badly, and tells a dishonest one nothing they did not
 * already know.
 */
public class PackageRejected extends RuntimeException {

    public PackageRejected(String message) {
        super(message);
    }
}
