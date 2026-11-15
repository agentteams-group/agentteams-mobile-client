package org.agentteams.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.agentteams.mobile.core.AccountSession
import org.agentteams.mobile.core.ServerAddress
import org.agentteams.mobile.core.json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context) {
    private val file = AtomicFile(context.applicationContext.noBackupFilesDir.resolve("account.enc"))
    private val alias = "org.agentteams.mobile.session"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "登录凭据已失效，请重新登录。" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    suspend fun load(): AccountSession? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        val bytes = file.readFully()
        require(bytes.size > 13 && bytes[0].toInt() == 12) { "无法读取本地登录信息，请重新登录。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        json.decodeFromString<AccountSession>(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString()).also {
            ServerAddress(it.matrix); ServerAddress(it.controller)
        }
    }

    suspend fun save(session: AccountSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val bytes = byteArrayOf(12) + cipher.iv + cipher.doFinal(json.encodeToString(session).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        check(!file.baseFile.exists()) { "未能清除本地登录信息，请重试。" }
        keyStore().apply { if (containsAlias(alias)) deleteEntry(alias) }
        Unit
    }
}
