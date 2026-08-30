package com.xenopsoftware.learn.identity.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The invitation token: minted once, stored only as a hash (T-1.9).
 *
 * <p>256 random bits from {@link SecureRandom}, because this is a bearer credential — anyone
 * holding it becomes the person it was issued for. That is also why only its SHA-256 reaches the
 * database: the row is a verifier, not a copy, so a backup, a support query or a leaked dump is
 * not a way in. Nobody, including us, can read an issued token back out of the system; a lost one
 * is re-issued, which rotates it.
 *
 * <p>No salt and no password hash. Both exist to make a <em>low-entropy, guessable</em> secret
 * expensive to attack; this secret is 256 random bits, so a single fast digest is exactly right,
 * and a slow one on a request path would be a cost with nothing bought.
 *
 * <p>No password is ever set by us and none is ever mailed. The token is returned to the caller
 * that asked for the invitation, once, and delivering it is their business — which is what keeps
 * "we never hold a credential" true even here.
 */
public final class InvitationToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private InvitationToken() {}

    /** A fresh token. Return it to the caller; store {@link #hash(String)} of it. */
    public static String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** The verifier stored in {@code app_user.invitation_token_hash}. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform; if it is missing, nothing about this
            // service's security assumptions holds and continuing would be the wrong answer.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
