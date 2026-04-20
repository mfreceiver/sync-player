package cn.vectory.syncplayer.sync.sftp

import android.content.Context
import android.util.Log
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.Properties

class SyncPlayerHostKeyVerifier(context: Context) : HostKeyVerifier {
    private val hostsFile = File(context.filesDir, "known_hosts.properties")
    private val props = Properties()

    init {
        if (hostsFile.exists()) {
            try {
                hostsFile.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                Log.e("HostKeyVerifier", "Failed to load known_hosts", e)
            }
        }
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
        val keyLabel = "$hostname:$port"
        val savedFingerprint = props.getProperty(keyLabel)

        return if (savedFingerprint == null) {
            // Trust on first use
            Log.i("HostKeyVerifier", "First time connecting to $keyLabel, saving fingerprint: $fingerprint")
            props.setProperty(keyLabel, fingerprint)
            saveProps()
            true
        } else {
            if (savedFingerprint == fingerprint) {
                true
            } else {
                Log.e("HostKeyVerifier", "Host key mismatch for $keyLabel! Saved: $savedFingerprint, Current: $fingerprint")
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }

    private fun saveProps() {
        try {
            hostsFile.outputStream().use { props.store(it, "SyncPlayer SFTP Known Hosts") }
        } catch (e: Exception) {
            Log.e("HostKeyVerifier", "Failed to save known_hosts", e)
        }
    }
}
