package cn.vectory.syncplayer.crypto

import java.io.IOException

/**
 * Thrown when a ciphertext does not begin with the AEAD v2 magic ("SPM2").
 *
 * Covers pre-v2 (AES/CTR, no MAC) ciphertexts and any malformed/truncated header.
 * After the v2 migration such data is unreadable; the caller must re-sync the file.
 * Modeled as [IOException] so existing I/O catch blocks handle it uniformly.
 */
class LegacyCiphertextException(
    message: String = "legacy or unrecognized ciphertext (expected AEAD v2 \"SPM2\")",
    cause: Throwable? = null,
) : IOException(message, cause)
