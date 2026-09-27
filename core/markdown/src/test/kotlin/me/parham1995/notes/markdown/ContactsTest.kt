package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Numbers and addresses that should dial or write, and the many that should not. Synthetic. */
class ContactsTest {
    private fun uris(text: String) = Contacts.find(text).map { it.uri }

    @Test
    fun `an international number dials with its prefix`() {
        assertThat(uris("Call +34 600 123 456 after nine.")).containsExactly("tel:+34600123456")
        assertThat(uris("desk: +1 (555) 010-9999")).containsExactly("tel:+15550109999")
    }

    @Test
    fun `a national number written with its zero dials`() {
        assertThat(uris("خانه: 0912 345 6789")).containsExactly("tel:09123456789")
    }

    @Test
    fun `persian digits dial as ascii ones`() {
        assertThat(uris("شماره: ۰۹۱۲۳۴۵۶۷۸۹")).containsExactly("tel:09123456789")
    }

    @Test
    fun `the span covers exactly the number`() {
        val text = "Call +34 600 123 456 after nine."
        val found = Contacts.find(text).single()

        assertThat(text.substring(found.start, found.end)).isEqualTo("+34 600 123 456")
    }

    @Test
    fun `dates, prices, years and ids are not phone numbers`() {
        assertThat(uris("recorded on 2026-09-07 at 14:00")).isEmpty()
        assertThat(uris("the rent is 1450 euros, deposit 2900")).isEmpty()
        assertThat(uris("budget for 1404, order 123456789012")).isEmpty()
        assertThat(uris("postcode 08029 Barcelona")).isEmpty()
        assertThat(uris("version 0.28.0")).isEmpty()
    }

    @Test
    fun `an email address writes to it`() {
        assertThat(uris("mail someone.else+notes@example.org, then wait")).containsExactly(
            "mailto:someone.else+notes@example.org",
        )
    }

    @Test
    fun `a handle is not an email address`() {
        assertThat(uris("ping @someone on the forum")).isEmpty()
    }

    @Test
    fun `a map link asks for the address it names`() {
        assertThat(Contacts.geo(" Carrer Example 12, 08001 Barcelona "))
            .isEqualTo("geo:0,0?q=Carrer+Example+12%2C+08001+Barcelona")
    }

    @Test
    fun `overlapping finds keep the first`() {
        val kept =
            with(Contacts) {
                listOf(
                    Contacts.Found(5, 20, Contacts.Kind.ADDRESS, "geo:a"),
                    Contacts.Found(0, 10, Contacts.Kind.PHONE, "tel:1"),
                    Contacts.Found(20, 25, Contacts.Kind.EMAIL, "mailto:x"),
                ).withoutOverlaps()
            }

        assertThat(kept.map { it.uri }).containsExactly("tel:1", "mailto:x").inOrder()
    }
}
