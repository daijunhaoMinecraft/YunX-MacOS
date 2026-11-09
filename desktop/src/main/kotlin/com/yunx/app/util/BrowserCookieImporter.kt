package com.yunx.app.util

import com.sun.jna.platform.win32.Crypt32Util
import com.yunx.app.AppContext
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一键从本机浏览器导入网盘登录 Cookie（方案 A）。
 *
 * - Chromium 系（Chrome / Edge / 360 / QQ / 搜狗 / Opera / Brave 等）：
 *   Network\Cookies（SQLite）中的 encrypted_value 为 "v10" 前缀 + Windows DPAPI 加密载荷，
 *   用 CryptUnprotectData 解密（同一 Windows 用户下可解）。
 *   "v20" 前缀为「应用绑定加密」（Chrome 127+ / 较新 Edge），本应用无法解密 → 跳过该 Cookie。
 * - Firefox：cookies.sqlite 中 value 为明文，直接读取。
 */
object BrowserCookieImporter {

    private const val TAG = "YunX-Cookie"

    enum class Kind { CHROMIUM, FIREFOX }

    data class BrowserInfo(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val profileDirs: List<File>,
        val macKeychainService: String? = null
    )

    data class FoundCookies(
        val browserName: String,
        val cookie: String,
        val count: Int
    )

    /** 平台 → 需要匹配的 Cookie 域名后缀 */
    fun domainSuffixes(platform: String): List<String> = when (platform) {
        "QUARK" -> listOf(".quark.cn")
        "UC" -> listOf(".uc.cn")
        "BAIDU" -> listOf(".baidu.com")
        "C139" -> listOf("mail.10086.cn", "yun.139.com", ".10086.cn")
        "PAN115" -> listOf(".115.com", ".115cdn.com")
        else -> emptyList()
    }

    /** 发现本机已安装浏览器的 Cookie 库目录（存在 cookies 文件才算） */
    fun discoverBrowsers(): List<BrowserInfo> {
        if (isMac()) return discoverMacBrowsers()
        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: return emptyList()
        val roaming = System.getenv("APPDATA")?.let { File(it) } ?: return emptyList()
        val out = mutableListOf<BrowserInfo>()

        // Chromium 系：<root>/Network/Cookies
        val chromiumCandidates = listOf(
            "Chrome" to listOf(File(local, "Google\\Chrome\\User Data")),
            "Edge" to listOf(File(local, "Microsoft\\Edge\\User Data")),
            "360极速浏览器" to listOf(File(local, "360Chrome\\Chrome\\User Data")),
            "360安全浏览器" to listOf(
                File(roaming, "360se6\\User Data"),
                File(roaming, "360se\\User Data"),
                File(local, "360se6\\User Data")
            ),
            "QQ浏览器" to listOf(File(local, "Tencent\\QQBrowser\\User Data")),
            "搜狗浏览器" to listOf(
                File(roaming, "SogouExplorer\\User Data"),
                File(local, "SogouExplorer\\User Data")
            ),
            "Opera" to listOf(File(roaming, "Opera Software\\Opera Stable")),
            "Brave" to listOf(File(local, "BraveSoftware\\Brave-Browser\\User Data")),
            "Vivaldi" to listOf(File(local, "Vivaldi\\User Data"))
        )
        for ((name, roots) in chromiumCandidates) {
            val existing = roots.filter { root ->
                root.isDirectory && File(root, "Network\\Cookies").exists()
            }
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing)
            }
        }

        // Firefox：<Profiles>/xxx/cookies.sqlite
        val ffRoot = File(roaming, "Mozilla\\Firefox\\Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
            }
        }
        return out
    }

    private fun discoverMacBrowsers(): List<BrowserInfo> {
        val home = File(System.getProperty("user.home"))
        val support = File(home, "Library/Application Support")
        val out = mutableListOf<BrowserInfo>()
        val chromium = listOf(
            Triple("Chrome", "Google/Chrome", "Chrome Safe Storage"),
            Triple("Edge", "Microsoft Edge", "Microsoft Edge Safe Storage"),
            Triple("Brave", "BraveSoftware/Brave-Browser", "Brave Safe Storage"),
            Triple("Vivaldi", "Vivaldi", "Vivaldi Safe Storage"),
            Triple("Opera", "com.operasoftware.Opera", "Opera Safe Storage"),
            Triple("Chromium", "Chromium", "Chromium Safe Storage")
        )
        for ((name, relative, service) in chromium) {
            val root = File(support, relative)
            if (!root.isDirectory) continue
            val profiles = buildList {
                if (cookiesFile(root) != null) add(root)
                root.listFiles()?.filter { it.isDirectory }?.forEach { child ->
                    if (cookiesFile(child) != null) add(child)
                }
            }.distinct()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, profiles, service)
            }
        }
        val ffRoot = File(support, "Firefox/Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").isFile }
                ?.toList().orEmpty()
            if (profiles.isNotEmpty()) out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
        }
        return out
    }

    private fun cookiesFile(profile: File): File? = listOf(
        File(profile, "Network/Cookies"),
        File(profile, "Cookies")
    ).firstOrNull { it.isFile }

    /** 从指定浏览器导入指定平台的 Cookie；未找到返回 null */
    fun importFrom(browser: BrowserInfo, domains: List<String>): FoundCookies? =
        when (browser.kind) {
            Kind.CHROMIUM -> importChromium(browser, domains)
            Kind.FIREFOX -> importFirefox(browser, domains)
        }

    // ---------- Chromium ----------

    private fun importChromium(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (root in browser.profileDirs) {
            val cookiesDb = cookiesFile(root) ?: continue
            val copy = File(AppContext.cacheDir, "browser-cookies-${System.nanoTime()}.db")
            try {
                cookiesDb.copyTo(copy, overwrite = true)
                val cookie = extractChromium(copy, domains, browser) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 ${browser.displayName} Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractChromium(dbFile: File, domains: List<String>, browser: BrowserInfo): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, encrypted_value, path FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    val baiduCookies = mutableListOf<BaiduBrowserCookie>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = decryptChromiumValue(
                            rs.getBytes(3), rs.getString(1), browser
                        ) ?: continue
                        if (name.isNotBlank()) {
                            pairs += "$name=$value"
                            baiduCookies += BaiduBrowserCookie(name, value, rs.getString(1), rs.getString(4))
                        }
                    }
                    if (pairs.isEmpty()) return null
                    return if (domains == domainSuffixes("BAIDU")) baiduCookieHeader(baiduCookies)
                        .takeIf { it.isNotBlank() } else pairs.joinToString("; ")
                }
            }
        }
    }

    /**
     * Chromium encrypted_value：
     * - "v10" 前缀 + DPAPI 载荷 → CryptUnprotectData 解密；
     * - "v20" 前缀（应用绑定加密，Chrome 127+）→ 返回 null（无法解密，跳过后整体提示）；
     * - 无前缀（个别旧版）→ 直接按 DPAPI 尝试。
     */
    private fun decryptChromiumValue(
        bytes: ByteArray,
        hostKey: String?,
        browser: BrowserInfo
    ): String? {
        if (bytes.size < 3) return null
        var offset = 0
        if (bytes[0] == 'v'.code.toByte() &&
            bytes[1] in '0'.code.toByte()..'9'.code.toByte() &&
            bytes[2] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            // v10 / v20 等版本前缀
            val version = (bytes[1] - '0'.code.toByte()) * 10 + (bytes[2] - '0'.code.toByte())
            if (version >= 20) return null // 应用绑定加密，无法解密
            offset = 3
        }
        val payload = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
        val plain = if (isMac()) {
            decryptMacChromium(payload, browser.macKeychainService) ?: return null
        } else {
            runCatching { Crypt32Util.cryptUnprotectData(payload) }.getOrNull() ?: return null
        }
        // Chromium 24+ 在明文前附 32 字节 SHA-256(host_key)；命中时剥掉，避免 Cookie 值带乱码。
        val value = if (plain.size > 32 && hostKey != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(hostKey.toByteArray(Charsets.UTF_8))
            if (plain.copyOfRange(0, 32).contentEquals(digest)) plain.copyOfRange(32, plain.size) else plain
        } else {
            plain
        }
        return String(value, Charsets.UTF_8)
    }

    private val macKeyCache = ConcurrentHashMap<String, SecretKeySpec>()

    private fun decryptMacChromium(payload: ByteArray, service: String?): ByteArray? = runCatching {
        val actualService = service ?: return@runCatching null
        val key = macKeyCache[actualService]
            ?: loadMacSafeStorageKey(actualService).also { macKeyCache[actualService] = it }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ByteArray(16) { 0x20 }))
        cipher.doFinal(payload)
    }.getOrNull()

    private fun loadMacSafeStorageKey(service: String): SecretKeySpec {
        val process = ProcessBuilder(
            "security", "find-generic-password", "-w", "-s", service
        ).redirectErrorStream(true).start()
        val password = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0 && password.isNotBlank()) {
            "无法读取 $service"
        }
        val spec = PBEKeySpec(password.toCharArray(), "saltysalt".toByteArray(), 1003, 128)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded,
            "AES"
        )
    }

    // ---------- Firefox ----------

    private fun importFirefox(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (profile in browser.profileDirs) {
            val dbFile = File(profile, "cookies.sqlite")
            if (!dbFile.exists()) continue
            val copy = File(AppContext.cacheDir, "ff-cookies-${System.nanoTime()}.db")
            try {
                dbFile.copyTo(copy, overwrite = true)
                val cookie = extractFirefox(copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 Firefox Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractFirefox(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host LIKE ?" }
            conn.prepareStatement(
                "SELECT host, name, value, path FROM moz_cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    val baiduCookies = mutableListOf<BaiduBrowserCookie>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = rs.getString(3)
                        if (name.isNotBlank()) {
                            pairs += "$name=$value"
                            baiduCookies += BaiduBrowserCookie(name, value, rs.getString(1), rs.getString(4))
                        }
                    }
                    if (pairs.isEmpty()) return null
                    return if (domains == domainSuffixes("BAIDU")) baiduCookieHeader(baiduCookies)
                        .takeIf { it.isNotBlank() } else pairs.joinToString("; ")
                }
            }
        }
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase()
            .let { it.contains("mac") || it.contains("darwin") }
}
