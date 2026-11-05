package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class ImageUrlsTest {
    @Test fun usesSmallerTmdbPreviewAndPreservesParameters() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg?token=x", imageUrlForWidth("https://image.tmdb.org/t/p/original/abc.jpg?token=x", 342))
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg", imageUrlForWidth("https://image.tmdb.org/t/p/w780/abc.jpg", 1280))
    }
    @Test fun leavesOtherHostsAndSpoofedDomainsUntouched() {
        val url = "https://image.tmdb.org.example.com/t/p/original/image.jpg"
        assertEquals(url, imageUrlForWidth(url, 342))
        assertEquals("https://other.test/a.jpg", imageUrlForWidth("https://other.test/a.jpg", 342))
    }
    @Test fun invalidPlaceholderIsNotTreatedAsAnImage() {
        assertEquals("", imageUrlForWidth("null", 342))
        assertEquals("", imageUrlForWidth("  ", 342))
    }
}
