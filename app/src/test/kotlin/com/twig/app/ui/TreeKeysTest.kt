package com.twig.app.ui

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TreeKeys]:树 key 与"上次位置"描述符的编解码。
 *
 * 重点在 **round-trip**:描述符存在 SharedPreferences 里,下次冷启动读到的是
 * **上一个版本写下的**数据。编码与解码但凡不对称,表现就是"升级之后位置恢复不了"——
 * 不崩溃、不报错,只是位置没了,真机上很难系统性走查。
 */
class TreeKeysTest {

    /** 本次会话:一台已连上的服务器 + 一台配置还在但没连上的。 */
    private val schemeOfConn: (String) -> String? = { label ->
        when (label) {
            "sftp://root@nas:22" -> "sftp1a2b"
            else -> null // 已删除,或本次会话还没展开过
        }
    }
    private val connLabelOf: (String) -> String? = { scheme ->
        if (scheme == "sftp1a2b") "sftp://root@nas:22" else null
    }

    private fun roundTrip(key: String) {
        val d = TreeKeys.descriptorOf(key, connLabelOf)
        assertTrue("应能编码: $key", d != null)
        assertEquals("round-trip 不对称: $key → $d", key, TreeKeys.keyOfDescriptor(d!!, schemeOfConn))
    }

    // ---- fileKey ----

    @Test
    fun fileKeyEncodesSchemeAndPath() {
        assertEquals("f:file:/sdcard/a.txt", TreeKeys.fileKey(XFile("file", "/sdcard/a.txt", false)))
        // 同名不同来源必须是不同的 key,否则两棵树的行会在各种表里互相顶掉
        assertTrue(
            TreeKeys.fileKey(XFile("file", "/x", true)) !=
                TreeKeys.fileKey(XFile("smb1a2b", "/x", true)),
        )
    }

    // ---- 描述符 round-trip ----

    @Test
    fun roundTripsEveryPersistableKind() {
        roundTrip("f:file:/sdcard/Download")
        roundTrip("f:apps:/user")
        roundTrip("f:sftp1a2b:/home/root/code")
        roundTrip("g:lan")
        roundTrip("s:sftp://root@nas:22")
        roundTrip("fav:abc123")
    }

    @Test
    fun roundTripsAwkwardButLegalPaths() {
        roundTrip("f:file:/sdcard/带空格 和中文/x")
        roundTrip("f:file:/sdcard/a:b:c")   // 路径里有冒号,不能和 scheme 分隔符混淆
        roundTrip("f:file:/")               // 根
    }

    /** 本地/应用两种前缀不能被通用的 `f:` 分支抢走(顺序敏感)。 */
    @Test
    fun localAndAppsTakePrecedenceOverConnBranch() {
        assertEquals("file\t/sdcard", TreeKeys.descriptorOf("f:file:/sdcard", connLabelOf))
        assertEquals("apps\t/user", TreeKeys.descriptorOf("f:apps:/user", connLabelOf))
    }

    // ---- 不可持久化 / 解不出来的情况 ----

    @Test
    fun unpersistableSourcesEncodeToNull() {
        // 压缩包内、restic、SAF、git 虚拟树:存不下"怎么再到达",不该写进描述符
        assertNull(TreeKeys.descriptorOf("f:zip:/sdcard/a.zip!/inner", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:restic9f/latest", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:saf:content%3A%2F%2Fx", connLabelOf))
        assertNull(TreeKeys.descriptorOf("restic:f:file:/x", connLabelOf))
    }

    /**
     * ★ 路径里含 '\t' / '\n'(Linux 上都是合法文件名字符)必须整条丢弃:
     * 描述符是 tab 分隔、整份列表是换行分隔,带进去会把**别的**条目也撕坏。
     * 宁可这一个节点不恢复。
     */
    @Test
    fun pathsWithSeparatorsAreDropped() {
        assertNull(TreeKeys.descriptorOf("f:file:/sdcard/tab\there", connLabelOf))
        assertNull(TreeKeys.descriptorOf("f:file:/sdcard/new\nline", connLabelOf))
    }

    /** 连接已被删除 → 编不出描述符;本次会话还没连上 → 解不出 key。 */
    @Test
    fun missingConnectionFailsBothDirections() {
        assertNull("连接已删除时不该写出 conn 描述符", TreeKeys.descriptorOf("f:ftp9z9z:/pub", connLabelOf))
        assertNull(
            "服务器本次没连上时解不出 scheme",
            TreeKeys.keyOfDescriptor("conn\tftp://other:21\t/pub", schemeOfConn),
        )
    }

    @Test
    fun malformedDescriptorsDecodeToNull() {
        assertNull(TreeKeys.keyOfDescriptor("", schemeOfConn))
        assertNull(TreeKeys.keyOfDescriptor("bogus\t/x", schemeOfConn))
        assertNull(TreeKeys.keyOfDescriptor("file", schemeOfConn))          // 缺参数
        assertNull(TreeKeys.keyOfDescriptor("conn\tsftp://root@nas:22", schemeOfConn)) // 缺路径
    }

    // ---- dirOfDescriptor ----

    @Test
    fun dirOfDescriptorBuildsNavigableDirs() {
        val local = TreeKeys.dirOfDescriptor("file\t/sdcard/Download", schemeOfConn)!!
        assertEquals("file", local.scheme)
        assertEquals("/sdcard/Download", local.path)
        assertTrue(local.isDir)

        val conn = TreeKeys.dirOfDescriptor("conn\tsftp://root@nas:22\t/home", schemeOfConn)!!
        assertEquals("sftp1a2b", conn.scheme)
        assertEquals("/home", conn.path)

        // 「应用」树只读
        assertEquals(false, TreeKeys.dirOfDescriptor("apps\t/user", schemeOfConn)!!.canWrite)
    }

    /** 分组/服务器/收藏不是"某个目录",不该被当成可导航目录解出来。 */
    @Test
    fun nonDirectoryKindsHaveNoDir() {
        assertNull(TreeKeys.dirOfDescriptor("group\tlan", schemeOfConn))
        assertNull(TreeKeys.dirOfDescriptor("server\tsftp://root@nas:22", schemeOfConn))
        assertNull(TreeKeys.dirOfDescriptor("fav\tabc", schemeOfConn))
    }

    // ---- 祖先链(手风琴折叠) ----

    private val rows = listOf(
        TreeKeys.Row("g:fav", 0),
        TreeKeys.Row("f:file:/sdcard", 0),
        TreeKeys.Row("f:file:/sdcard/a", 1),
        TreeKeys.Row("f:file:/sdcard/a/x", 2),
        TreeKeys.Row("f:file:/sdcard/a/x/deep", 3),
        TreeKeys.Row("f:file:/sdcard/b", 1),
        TreeKeys.Row("g:lan", 0),
    )

    @Test
    fun ancestorsWalkUpByIndentation() {
        assertEquals(
            setOf("f:file:/sdcard", "f:file:/sdcard/a", "f:file:/sdcard/a/x"),
            TreeKeys.ancestorKeys(rows, "f:file:/sdcard/a/x/deep"),
        )
    }

    @Test
    fun ancestorsStopAtTopLevel() {
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "f:file:/sdcard"))
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "g:lan"))
    }

    /** 只收祖先,不收同级的兄弟,也不收前面那棵树里的行。 */
    @Test
    fun ancestorsExcludeSiblingsAndEarlierBranches() {
        val a = TreeKeys.ancestorKeys(rows, "f:file:/sdcard/b")
        assertEquals(setOf("f:file:/sdcard"), a)
    }

    @Test
    fun unknownKeyHasNoAncestors() {
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(rows, "f:file:/nope"))
        assertEquals(emptySet<String>(), TreeKeys.ancestorKeys(emptyList(), "anything"))
    }
}
