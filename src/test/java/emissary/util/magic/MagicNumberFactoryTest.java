package emissary.util.magic;

import emissary.test.core.junit5.UnitTest;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicNumberFactoryTest extends UnitTest {

    @Test
    void testByte() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 byte 0x41 FOO").test(Hex.decodeHex("41")),
                "byte should match a single byte");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 byte 0x41 FOO").test(Hex.decodeHex("42")),
                "byte should not match a different single byte");
    }

    @Test
    void testShort() throws ParseException, DecoderException {
        // SHORT is big-endian 2-byte (native-endian on big-endian machines, but the code treats it as big-endian)
        assertTrue(MagicNumberFactory.buildMagicNumber("0 short 0x4142 FOO").test(Hex.decodeHex("4142")),
                "short should match two bytes big-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 short 0x4142 FOO").test(Hex.decodeHex("4241")),
                "short should not match reversed bytes");
    }

    @Test
    void testLong() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 long 0x41424344 FOO").test(Hex.decodeHex("41424344")),
                "long should match four bytes big-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 long 0x41424344 FOO").test(Hex.decodeHex("44434241")),
                "long should not match reversed bytes");
    }

    @Test
    void testString() throws ParseException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 string ABCD FOO").test("ABCD".getBytes()),
                "string should match the exact bytes");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 string ABCD FOO").test("ABCE".getBytes()),
                "string should not match a slightly different string");
    }

    @Test
    void testBeshort() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 beshort 0x4142 FOO").test(Hex.decodeHex("4142")),
                "beshort should match two bytes big-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 beshort 0x4142 FOO").test(Hex.decodeHex("4241")),
                "beshort should not match reversed bytes");
    }

    @Test
    void testBelong() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 belong 0x41424344 FOO").test(Hex.decodeHex("41424344")),
                "belong should match four bytes big-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 belong 0x41424344 FOO").test(Hex.decodeHex("44434241")),
                "belong should not match reversed bytes");
    }

    @Test
    void testBelongMask() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 belong&0x000f0000 0x000e0000 MACHO");
        assertTrue(m.test(Hex.decodeHex("CAFEBABE")), "CAFEBABE keeps the digit E after masking, so it matches");
        assertFalse(m.test(Hex.decodeHex("CAFFBABE")), "CAFFBABE keeps F instead of E, so it must not match");
        assertFalse(m.test(Hex.decodeHex("CAFDBABE")), "CAFDBABE keeps D instead of E, so it must not match");
    }

    @Test
    void testLeshort() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 leshort 0x4142 FOO").test(Hex.decodeHex("4241")),
                "leshort should match two bytes little-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 leshort 0x4142 FOO").test(Hex.decodeHex("4142")),
                "leshort should not match big-endian ordering");
    }

    @Test
    void testLelong() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 lelong 0x41424344 FOO").test(Hex.decodeHex("44434241")),
                "lelong should match four bytes little-endian");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 lelong 0x41424344 FOO").test(Hex.decodeHex("41424344")),
                "lelong should not match big-endian ordering");
    }

    @Test
    void testRegex() {
        ParseException e = assertThrows(ParseException.class,
                () -> MagicNumberFactory.buildMagicNumber("0 regex x FOO"),
                "regex type should be rejected");
        assertTrue(e.getMessage().contains(MagicNumberFactory.UNSUPPORTED_DATATYPE_MSG_REGEX),
                "regex must be recognized as a skippable regex-type failure, but got: " + e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"qdate", "beqdate", "leqdate", "qldate", "beldate-0x7C25B080", "bedate-0x7C25B080"})
    void testQuadAndDashedDate(String type) {
        ParseException e = assertThrows(ParseException.class,
                () -> MagicNumberFactory.buildMagicNumber("0 " + type + " x FOO"),
                type + ": eight-byte and dash-suffixed date types should be rejected");
        assertTrue(e.getMessage().contains("Unsupported Data Type: "),
                type + " must fail with the ordinary 'Unsupported Data Type' message, but got: " + e.getMessage());
        assertFalse(e.getMessage().contains(MagicNumberFactory.UNSUPPORTED_DATATYPE_MSG_UNSIGNED),
                type + " must not be mistaken for a skippable unsigned-type failure: " + e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ulequad", "ustring"})
    void testUnknownUnsigned(String type) {
        ParseException e = assertThrows(ParseException.class,
                () -> MagicNumberFactory.buildMagicNumber("0 " + type + " x FOO"),
                "unknown unsigned type names must still be rejected");
        assertTrue(e.getMessage().contains(MagicNumberFactory.UNSUPPORTED_DATATYPE_MSG_UNSIGNED),
                "the loader must recognize this failure as one it can safely skip, but got: " + e.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"BESHORT, 4142, 4142", "BELONG, 41424344, 41424344", "LESHORT, 0102, 0201", "LELONG, 01020304, 04030201"})
    void testCaseInsensitive(String type, String valueHex, String dataHex) throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 " + type + " 0x" + valueHex + " FOO").test(Hex.decodeHex(dataHex)),
                type + " type should be case-insensitive");
    }

    @Test
    void testGreaterThanOperator() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 belong >10 HUGE").test(Hex.decodeHex("0000000B")),
                "11 must beat 10");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 belong >10 HUGE").test(Hex.decodeHex("00000009")),
                "9 must not beat 10");
    }

    @Test
    void testLessThanOperator() throws ParseException, DecoderException {
        MagicNumber signed = MagicNumberFactory.buildMagicNumber("0 belong <0 OLD");
        assertTrue(signed.test(Hex.decodeHex("FFFFFFFF")), "signed belong: FFFF FFFF (-1) must be less than 0");
        assertTrue(signed.test(Hex.decodeHex("80000000")), "signed belong: 8000 0000 (most negative) must be less than 0");
        assertFalse(signed.test(Hex.decodeHex("00000009")), "signed belong: a positive 9 must not be less than 0");
    }

    @Test
    void testComparisons() throws ParseException, DecoderException {
        assertFalse(MagicNumberFactory.buildMagicNumber("0 belong >10 HUGE").test(Hex.decodeHex("FFFFFFFF")),
                "signed belong: FFFF FFFF is -1, which must not beat 10");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 belong >10 HUGE").test(Hex.decodeHex("00000009")),
                "9 must not beat 10");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 belong 0xFFFFFFFF MAXED").test(Hex.decodeHex("FFFFFFFF")),
                "an all-bits-set value must equal itself");
    }

    @Test
    void testComparisonsUnsigned() throws ParseException, DecoderException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 ubelong >10 HUGE").test(Hex.decodeHex("FFFFFFFF")),
                "unsigned ubelong: FFFF FFFF is 4294967295, which must beat 10");
        assertFalse(MagicNumberFactory.buildMagicNumber("0 ubelong >10 HUGE").test(Hex.decodeHex("00000009")),
                "9 must not beat 10");
        MagicNumber unsigned = MagicNumberFactory.buildMagicNumber("0 ubelong <0x80000000 OLD");
        assertTrue(unsigned.test(Hex.decodeHex("7FFFFFFF")), "unsigned 7FFF FFFF (2147483647) must be smaller than 8000 0000 (2147483648)");
        assertFalse(unsigned.test(Hex.decodeHex("80000001")), "unsigned 8000 0001 (2147483649) must not be smaller than 8000 0000");
    }

    @Test
    void testStringSubstitution() throws ParseException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 string x SUBST");
        assertTrue(m.isSubstitute(), "the 'x' value should set the substitute flag");
    }

    @Test
    void testStringSubstitutionDescribe() throws ParseException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 string x %s");
        assertEquals("A", m.describe(new byte[] {0x41}), "a %s string substitution should emit the single byte as text");
    }

    @Test
    void testTwoByteFileStillSubstitutesSingleByte() throws ParseException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 string x %s");
        assertEquals("A", m.describe(new byte[] {0x41, 0x42}),
                "a two-byte value must not skip the substitution even though there is no trailing byte");
    }

    @Test
    void testUnsignedAliasesSigned() throws ParseException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 ubelong 0x41424344 FOO").test("ABCD".getBytes()),
                "ubelong should work just like belong");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 belong 0x41424344 FOO").test("ABCD".getBytes()),
                "belong is expected to match here");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 ulelong 0x41424344 FOO").test("DCBA".getBytes()),
                "ulelong should work just like lelong");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 lelong 0x41424344 FOO").test("DCBA".getBytes()),
                "lelong is expected to match here");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 ubeshort 0x4142 FOO").test("AB".getBytes()),
                "ubeshort should work just like beshort");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 uleshort 0x4142 FOO").test("BA".getBytes()),
                "uleshort should work just like leshort");
        assertTrue(MagicNumberFactory.buildMagicNumber("0 ubyte 0x41 FOO").test("A".getBytes()),
                "ubyte should work just like byte");
    }

    @Test
    void testUnsignedWithMask() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 ubelong&0x000f0000 0x000e0000 MACHO");
        assertTrue(m.test(Hex.decodeHex("CAFEBABE")), "CAFEBABE keeps the digit E after masking, so it matches");
        assertFalse(m.test(Hex.decodeHex("CAFFBABE")), "CAFFBABE keeps F instead of E, so it must not match");
        assertFalse(m.test(Hex.decodeHex("CAFDBABE")), "CAFDBABE keeps D instead of E, so it must not match");
    }

    @ParameterizedTest
    @CsvSource({"UBELONG, ABCD", "ULELONG, DCBA", "BEDATE, ABCD", "LeDate, DCBA"})
    void testUnsignedCaseInsensitive(String type, String data) throws ParseException {
        assertTrue(MagicNumberFactory.buildMagicNumber("0 " + type + " 0x41424344 FOO").test(data.getBytes()),
                type + " with odd capitalization should behave exactly like its lower-case spelling");
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "ldate", "bedate", "beldate"})
    void testSignedBigEndianDate(String type) throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 " + type + " >0 STAMP");
        assertTrue(m.test(Hex.decodeHex("00000002")), type + " reads four bytes front-to-back, so 2 beats 0");
        assertTrue(m.test(Hex.decodeHex("7FFFFFFF")), type + " is a signed date, so the positive 7F FF FF FF beats 0");
        assertFalse(m.test(Hex.decodeHex("FFFFFFFF")), type + " is a signed date, so FFFF FFFF (-1) must not beat 0");
        assertFalse(m.test(Hex.decodeHex("80000000")), type + " is a signed date, so 8000 0000 (most negative) must not beat 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"udate", "uldate", "ubedate", "ubeldate"})
    void testUnsignedBigEndianDate(String type) throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 " + type + " >0x80000000 STAMP");
        assertTrue(m.test(Hex.decodeHex("80000001")), type + " reads front-to-back unsigned, so 80 00 00 01 is bigger than 80 00 00 00");
        assertFalse(m.test(Hex.decodeHex("7FFFFFFF")), type + " reads front-to-back unsigned, so 7F FF FF FF is smaller than 80 00 00 00");
        assertTrue(m.test(Hex.decodeHex("FFFFFFFF")), type + " is unsigned, so FFFF FFFF must beat any positive threshold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "ldate", "bedate", "beldate"})
    void testSignedNegativeDate(String type) throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 " + type + " <0 NEGATIVE");
        assertTrue(m.test(Hex.decodeHex("FFFFFFFF")), type + " is a signed date, so FFFF FFFF (-1) must be less than 0");
        assertFalse(m.test(Hex.decodeHex("00000001")), type + " is a signed date, so 1 must not be less than 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledate", "leldate", "uledate", "uleldate"})
    void testLittleEndianDate(String type) throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 " + type + " >0x00000100 STAMP");
        assertTrue(m.test(Hex.decodeHex("00020000")), type + " reads the four bytes back-to-front, so 512 is bigger than 256");
        assertFalse(m.test(Hex.decodeHex("00010000")), type + " reads the four bytes back-to-front, so 256 is not bigger than 256");
    }

    @Test
    void testSignedNumericDescription() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 belong x %d");
        assertEquals("-1", m.describe(Hex.decodeHex("FFFFFFFF")), "a signed belong must show -1 for FFFF FFFF");
        assertEquals("100", m.describe(Hex.decodeHex("00000064")), "a signed belong must show 100 for 00000064");
    }

    @Test
    void testUnsignedNumericDescription() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 ubelong x %d");
        assertEquals("4294967295", m.describe(Hex.decodeHex("FFFFFFFF")), "an unsigned ubelong must show 4294967295 for FFFF FFFF");
        assertEquals("100", m.describe(Hex.decodeHex("00000064")), "an unsigned ubelong must show 100 for 00000064");
    }

    @Test
    void testUnsignedRejectsNegativeValue() throws ParseException {
        ParseException e = assertThrows(ParseException.class,
                () -> MagicNumberFactory.buildMagicNumber("0 ubyte >-2 FOO"),
                "an unsigned type must reject a negative literal");
        assertTrue(e.getMessage().contains("Negative value not allowed"), "unexpected message: " + e.getMessage());
    }

    @Test
    void testSignedAcceptsNegativeLiteral() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 byte -2 FOO");
        assertTrue(m.test(Hex.decodeHex("FE")), "signed -2 must equal the byte 0xFE");
        assertFalse(m.test(Hex.decodeHex("FF")), "signed -1 must not equal -2");
    }

    @Test
    void testSignedNegativeComparison() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 byte >-2 FOO");
        assertTrue(m.test(Hex.decodeHex("FF")), "signed -1 must be greater than -2");
        assertFalse(m.test(Hex.decodeHex("FE")), "signed -2 must not be greater than -2");
        assertFalse(m.test(Hex.decodeHex("FD")), "signed -3 must not be greater than -2");
    }

    @Test
    void testDateWithDecimalValue() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 ledate >1000000000 RECENT");
        assertTrue(m.test(Hex.decodeHex("80B15465")), "these bytes are 1700000000 seconds");
        assertFalse(m.test(Hex.decodeHex("00CA9A3B")), "these bytes are 1000000000 seconds");
    }

    @Test
    void testDateComparisonOperators() throws ParseException, DecoderException {
        MagicNumber equalsRule = MagicNumberFactory.buildMagicNumber("0 bedate 1234567890 EPOCH");
        assertTrue(equalsRule.test(Hex.decodeHex("499602D2")), "no sign in front of the value means the timestamps must match exactly");
        assertFalse(equalsRule.test(Hex.decodeHex("499602D3")), "one second away from 1234567890 should not count as an exact match");

        MagicNumber lessThanRule = MagicNumberFactory.buildMagicNumber("0 bedate <0x00000100 OLD");
        assertTrue(lessThanRule.test(Hex.decodeHex("000000FF")), "'<' matches timestamps smaller than 0x00000100");
        assertFalse(lessThanRule.test(Hex.decodeHex("00000100")), "'<' must reject a timestamp equal to 0x00000100");
        assertFalse(lessThanRule.test(Hex.decodeHex("00000101")), "'<' must reject a timestamp bigger than 0x00000100");
    }

    @Test
    void testUnsignedDateComparison() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 ubedate <0x00000100 OLD");
        assertTrue(m.test(Hex.decodeHex("000000FF")), "unsigned date matches timestamps smaller than 0x00000100");
        assertFalse(m.test(Hex.decodeHex("00000100")), "unsigned date rejects a timestamp equal to the threshold");
        assertFalse(m.test(Hex.decodeHex("FFFFF0FF")), "unsigned date FFFF F0FF is a large positive timestamp, so must not be less than 0x00000100");
    }

    @Test
    void testDateWithMask() throws ParseException, DecoderException {
        MagicNumber m = MagicNumberFactory.buildMagicNumber("0 bedate&0x000000ff 0x00000042 MASKED");
        assertTrue(m.test(Hex.decodeHex("80000042")), "only the last byte survives the mask");
        assertFalse(m.test(Hex.decodeHex("80000043")), "after masking, the last byte does not equal 0x42");
    }
}
