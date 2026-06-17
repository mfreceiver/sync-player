package cn.vectory.syncplayer.provider

import org.junit.Assert.*
import org.junit.Test

class DocumentIdTest {

    // --- encode / decode roundtrip ---

    @Test
    fun `encode and decode roundtrip for Root`() {
        val encoded = DocumentId.encode(DocumentId.Root)
        assertEquals("root", encoded)
        assertEquals(DocumentId.Root, DocumentId.decode(encoded))
    }

    @Test
    fun `encode and decode roundtrip for Relation`() {
        val id = DocumentId.Relation(42L)
        val encoded = DocumentId.encode(id)
        assertEquals("rel:42", encoded)
        assertEquals(id, DocumentId.decode(encoded))
    }

    @Test
    fun `encode and decode roundtrip for Directory`() {
        val id = DocumentId.Directory(7L, "path/to/folder")
        val encoded = DocumentId.encode(id)
        assertEquals("dir:7:path/to/folder", encoded)
        assertEquals(id, DocumentId.decode(encoded))
    }

    @Test
    fun `encode and decode roundtrip for Directory with empty path`() {
        val id = DocumentId.Directory(1L, "")
        val encoded = DocumentId.encode(id)
        assertEquals("dir:1:", encoded)
        assertEquals(id, DocumentId.decode(encoded))
    }

    @Test
    fun `encode and decode roundtrip for Video`() {
        val id = DocumentId.Video(99L)
        val encoded = DocumentId.encode(id)
        assertEquals("vid:99", encoded)
        assertEquals(id, DocumentId.decode(encoded))
    }

    // --- decode returns null on malformed input ---

    @Test
    fun `decode returns null for empty string`() {
        assertNull(DocumentId.decode(""))
    }

    @Test
    fun `decode returns null for unknown prefix`() {
        assertNull(DocumentId.decode("unknown:123"))
    }

    @Test
    fun `decode returns null for rel without valid Long`() {
        assertNull(DocumentId.decode("rel:not_a_number"))
    }

    @Test
    fun `decode returns null for vid without valid Long`() {
        assertNull(DocumentId.decode("vid:abc"))
    }

    @Test
    fun `decode returns null for dir without colon after relationId`() {
        assertNull(DocumentId.decode("dir:5")) // missing ":path"
    }

    @Test
    fun `decode returns null for dir with empty relationId`() {
        assertNull(DocumentId.decode("dir::path")) // colonIndex <= 0
    }

    @Test
    fun `decode returns null for dir with invalid relationId`() {
        assertNull(DocumentId.decode("dir:abc:path"))
    }

    // --- decodeOrThrow ---

    @Test
    fun `decodeOrThrow returns same as decode for valid ids`() {
        assertEquals(DocumentId.Root, DocumentId.decodeOrThrow("root"))
        assertEquals(DocumentId.Video(1L), DocumentId.decodeOrThrow("vid:1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeOrThrow throws for invalid id`() {
        DocumentId.decodeOrThrow("garbage")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeOrThrow throws for empty string`() {
        DocumentId.decodeOrThrow("")
    }
}
