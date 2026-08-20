package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Reducing HTML mail to words")
class HtmlTest {

    @Nested
    @DisplayName("Tags become whitespace, never nothing")
    class Separation {

        @Test
        @DisplayName("adjacent table cells do not fuse")
        void tableCells() {
            // The failure this prevents is silent and expensive: "Rs 500" and
            // "debited" fusing into "Rs 500debited" leaves an amount no parser
            // will ever find.
            String text = Html.toText("<tr><td>Rs 500</td><td>debited</td></tr>");
            assertThat(text).contains("Rs 500").contains("debited");
            assertThat(text).doesNotContain("500debited");
        }

        @Test
        @DisplayName("inline emphasis does not fuse")
        void inlineTags() {
            assertThat(Html.toText("<p>Rs.<b>450</b>.00 debited</p>"))
                    .doesNotContain("<")
                    .contains("450");
        }

        @Test
        @DisplayName("line breaks become line breaks")
        void breaks() {
            assertThat(Html.toText("Rs 500 debited<br>Ref 12345"))
                    .isEqualTo("Rs 500 debited\nRef 12345");
        }
    }

    @Nested
    @DisplayName("Noise is removed entirely")
    class Noise {

        @Test
        @DisplayName("stylesheets, which are full of numbers that look like amounts")
        void styles() {
            String text = Html.toText(
                    "<style>.a{width:500px;margin:12.50em}</style><p>Rs 99 debited</p>");
            assertThat(text).doesNotContain("500").doesNotContain("12.50").contains("Rs 99");
        }

        @Test
        @DisplayName("scripts")
        void scripts() {
            assertThat(Html.toText("<script>var total = 9999;</script><p>Rs 99 debited</p>"))
                    .doesNotContain("9999");
        }

        @Test
        @DisplayName("the head, including the title a template repeats")
        void head() {
            assertThat(Html.toText("<head><title>Bank Alert 1234</title></head><body>Rs 99</body>"))
                    .doesNotContain("1234");
        }

        @Test
        @DisplayName("comments")
        void comments() {
            assertThat(Html.toText("<!-- tracking id 5555 --><p>Rs 99</p>"))
                    .doesNotContain("5555");
        }
    }

    @Nested
    @DisplayName("Entities that carry meaning are decoded")
    class Entities {

        @Test
        @DisplayName("the rupee sign, without which an amount is unreadable")
        void rupee() {
            assertThat(Html.toText("<p>&#8377;450 debited</p>")).contains("₹450");
        }

        @Test
        @DisplayName("ampersands, which are all over merchant names")
        void ampersand() {
            assertThat(Html.toText("<p>Paid to MARKS &amp; SPENCER</p>"))
                    .contains("MARKS & SPENCER");
        }

        @Test
        @DisplayName("a literal escaped entity is not double-decoded")
        void noDoubleDecoding() {
            // "&amp;pound;" is the text "&pound;", not a pound sign.
            assertThat(Html.toText("<p>&amp;pound;</p>")).isEqualTo("&pound;");
        }

        @Test
        @DisplayName("non-breaking spaces become ordinary ones")
        void nbsp() {
            assertThat(Html.toText("<p>Rs.&nbsp;450</p>")).isEqualTo("Rs. 450");
        }
    }

    @Nested
    @DisplayName("Recognising HTML at all")
    class Detection {

        @Test
        @DisplayName("markup is recognised even when the provider called it text")
        void detectsMarkup() {
            assertThat(Html.looksLikeHtml("<div>Rs 450</div>")).isTrue();
            assertThat(Html.looksLikeHtml("Rs 450<br>debited")).isTrue();
        }

        @Test
        @DisplayName("plain text with comparison signs is not mistaken for markup")
        void plainTextIsNotHtml() {
            assertThat(Html.looksLikeHtml("Balance < 500 after Rs 450 debited")).isFalse();
            assertThat(Html.looksLikeHtml(null)).isFalse();
        }
    }
}
