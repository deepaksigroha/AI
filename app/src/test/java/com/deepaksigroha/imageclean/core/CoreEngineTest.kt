package com.deepaksigroha.imageclean.core

import com.deepaksigroha.imageclean.core.ai.AiProvenanceScanner
import com.deepaksigroha.imageclean.core.ai.AiVerdict
import com.deepaksigroha.imageclean.core.ai.EvidenceKind
import com.deepaksigroha.imageclean.core.ai.ExifProvenance
import com.deepaksigroha.imageclean.core.ai.IgAssessment
import com.deepaksigroha.imageclean.core.ai.IgInput
import com.deepaksigroha.imageclean.core.ai.InstagramChecker
import com.deepaksigroha.imageclean.core.c2pa.C2paReader
import com.deepaksigroha.imageclean.core.c2pa.C2paReport
import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.core.c2pa.CborDecoder
import com.deepaksigroha.imageclean.core.c2pa.CborEncoder
import com.deepaksigroha.imageclean.core.c2pa.CborValue
import com.deepaksigroha.imageclean.core.c2pa.CheckResult
import com.deepaksigroha.imageclean.core.c2pa.TrustEvaluator
import com.deepaksigroha.imageclean.core.clean.JpegRewriter
import com.deepaksigroha.imageclean.core.clean.PngRewriter
import com.deepaksigroha.imageclean.core.clean.XmpBuilder
import com.deepaksigroha.imageclean.core.container.ContainerScanner
import com.deepaksigroha.imageclean.core.meta.AiPatterns
import com.deepaksigroha.imageclean.core.meta.IptcParser
import com.deepaksigroha.imageclean.core.meta.XmpParser
import com.deepaksigroha.imageclean.core.model.ImageFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEngineTest {

    private val noTrust = TrustEvaluator { "test: no anchors" }
    private val noExif = ExifProvenance(null, null, null, null, null, null, false)

    private val aiXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
          xmlns:dc="http://purl.org/dc/elements/1.1/"
          xmp:CreatorTool="Adobe Firefly"
          Iptc4xmpExt:DigitalSourceType="http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia">
          <dc:creator><rdf:Seq><rdf:li>Deepak</rdf:li></rdf:Seq></dc:creator>
          <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">© Deepak</rdf:li></rdf:Alt></dc:rights>
        </rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test fun detectsFormats() {
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(TestImages.jpeg()))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(TestImages.png()))
        val webp = "RIFF".toByteArray() + Bytes.be32(0) + "WEBPVP8 ".toByteArray()
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(webp))
        val heic = Bytes.be32(24) + "ftypheic".toByteArray() + ByteArray(12)
        assertEquals(ImageFormat.HEIF, ImageFormat.detect(heic))
    }

    @Test fun scansJpegSegments() {
        val img = TestImages.jpeg(TestImages.exifSeg(), TestImages.xmpSeg(aiXmp), TestImages.iptcSeg(80 to "Deepak", 116 to "© 2026"), TestImages.comSeg("hello"))
        val c = ContainerScanner.scan(img)
        assertTrue(c.hasExif); assertTrue(c.hasXmp); assertTrue(c.hasIptc); assertFalse(c.hasC2pa)
        assertEquals(listOf("hello"), c.comments)
        val iptc = IptcParser.parse(c.iptcIim!!)
        assertEquals(listOf("Deepak"), iptc.creator)
        assertEquals("© 2026", iptc.copyright)
    }

    @Test fun parsesXmp() {
        val x = XmpParser.parse(listOf(aiXmp))
        assertEquals("Adobe Firefly", x.creatorTool)
        assertTrue(x.digitalSourceType!!.endsWith("trainedAlgorithmicMedia"))
        assertEquals(listOf("Deepak"), x.creators)
        assertEquals("© Deepak", x.rights)
    }

    @Test fun matchesSourceTypes() {
        assertEquals(AiPatterns.SourceType.TRAINED_ALGORITHMIC, AiPatterns.matchSourceType("http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"))
        assertEquals(AiPatterns.SourceType.COMPOSITE_TRAINED, AiPatterns.matchSourceType("compositeWithTrainedAlgorithmicMedia"))
        assertEquals(AiPatterns.SourceType.DIGITAL_CAPTURE, AiPatterns.matchSourceType("http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"))
        assertNull(AiPatterns.matchSourceType("hello world"))
        assertEquals("Midjourney", AiPatterns.matchGenerator("Made in Midjourney v6"))
        assertNull(AiPatterns.matchGenerator("Adobe Photoshop 25.0"))
        assertNull(AiPatterns.matchGenerator("Canon EOS R5"))
    }

    @Test fun cborRoundTrip() {
        val v = CborValue.Map(linkedMapOf<CborValue, CborValue>(
            CborValue.Text("a") to CborValue.UInt(300), CborValue.Text("b") to CborValue.NInt(-7),
            CborValue.Text("c") to CborValue.Arr(listOf(CborValue.Bytes(byteArrayOf(1, 2, 3)), CborValue.Bool(true), CborValue.Null)),
        ))
        val back = CborDecoder.decode(CborEncoder.encode(v))
        assertEquals(300L, back["a"]?.asLong()); assertEquals(-7L, back["b"]?.asLong())
        assertArrayEquals(byteArrayOf(1, 2, 3), back["c"]!!.asArr()!![0].asBytes())
    }

    @Test fun aiScannerUsesXmpDisclosure() {
        val img = TestImages.jpeg(TestImages.xmpSeg(aiXmp))
        val c = ContainerScanner.scan(img)
        val r = AiProvenanceScanner.scan(c, noExif, null, XmpParser.parse(c.xmpPackets), C2paReport.NONE)
        assertEquals(AiVerdict.AI_GENERATED, r.verdict)
        assertTrue(r.evidence.all { it.kind == EvidenceKind.METADATA })
    }

    @Test fun aiScannerUndeterminedWithoutMetadata() {
        val c = ContainerScanner.scan(TestImages.jpeg())
        val r = AiProvenanceScanner.scan(c, noExif, null, null, C2paReport.NONE)
        assertEquals(AiVerdict.UNDETERMINED, r.verdict)
    }

    @Test fun aiScannerReadsDiffusionPngParameters() {
        val png = TestImages.png(TestImages.text("parameters", "a cat\nNegative prompt: dog\nSteps: 20, Sampler: Euler a, CFG scale: 7"))
        val c = ContainerScanner.scan(png)
        assertEquals(1, c.textChunks.size)
        assertEquals(AiVerdict.AI_GENERATED, AiProvenanceScanner.scan(c, noExif, null, null, C2paReport.NONE).verdict)
    }

    @Test fun c2paDataHashValidatesAndDetectsTampering() {
        val good = TestImages.jpegWithC2pa()
        val c = ContainerScanner.scan(good)
        assertTrue("manifest found", c.hasC2pa)
        val rep = C2paReader(noTrust).read(c.c2paJumbf, good)
        val binding = rep.checks.first { it.name.startsWith("Content binding") }
        assertEquals(CheckResult.PASS, binding.result)
        assertEquals(CheckResult.PASS, rep.checks.first { it.name == "Assertion integrity" }.result)
        // Signature has no certificate → invalid overall
        assertEquals(C2paState.INVALID, rep.state)
        assertTrue(rep.aiSourceTypes.any { it.endsWith("trainedAlgorithmicMedia") })
        assertEquals("Test Generator/1.0", rep.active?.claimGenerator)

        val bad = TestImages.jpegWithC2pa(tamper = true)
        val rep2 = C2paReader(noTrust).read(ContainerScanner.scan(bad).c2paJumbf, bad)
        assertEquals(CheckResult.FAIL, rep2.checks.first { it.name.startsWith("Content binding") }.result)

        // AI scanner still reports the AI claim, but only as signed-unverified evidence
        val ai = AiProvenanceScanner.scan(c, noExif, null, null, rep)
        assertEquals(AiVerdict.AI_GENERATED, ai.verdict)
        assertFalse(ai.cryptographic)
    }

    @Test fun jpegRewriterRemovesSegmentsLosslessly() {
        val img = TestImages.jpeg(TestImages.exifSeg(), TestImages.xmpSeg(aiXmp), TestImages.comSeg("secret"))
        val out = JpegRewriter.removeSegments(img, setOf(JpegRewriter.Seg.COMMENT, JpegRewriter.Seg.EXIF))
        val c = ContainerScanner.scan(out)
        assertFalse(c.hasExif); assertTrue(c.comments.isEmpty()); assertTrue(c.hasXmp)
        // entropy-coded data unchanged
        val tail = img.copyOfRange(img.size - 7, img.size)
        assertArrayEquals(tail, out.copyOfRange(out.size - 7, out.size))
    }

    @Test fun jpegRewriterNeverDropsC2pa() {
        val img = TestImages.jpegWithC2pa()
        val out = JpegRewriter.removeSegments(img, JpegRewriter.Seg.entries.toSet())
        assertTrue(ContainerScanner.scan(out).hasC2pa)
    }

    @Test fun disclosureSurvivesXmpReplacement() {
        val img = TestImages.jpeg(TestImages.xmpSeg(aiXmp))
        val out = JpegRewriter.removeSegments(img, setOf(JpegRewriter.Seg.XMP), XmpBuilder.disclosurePacket("trainedAlgorithmicMedia"))
        val x = XmpParser.parse(ContainerScanner.scan(out).xmpPackets)
        assertNull(x.creatorTool)
        assertEquals(AiPatterns.SourceType.TRAINED_ALGORITHMIC, AiPatterns.matchSourceType(x.digitalSourceType!!))
        val merged = XmpBuilder.mergeDisclosure("<x:xmpmeta><rdf:RDF><rdf:Description rdf:about=\"\" xmp:CreatorTool=\"Tool\"/></rdf:RDF></x:xmpmeta>", "compositeWithTrainedAlgorithmicMedia")
        val mx = XmpParser.parse(listOf(merged))
        assertEquals("Tool", mx.creatorTool)
        assertEquals(AiPatterns.SourceType.COMPOSITE_TRAINED, AiPatterns.matchSourceType(mx.digitalSourceType!!))
    }

    @Test fun pngRewriterDropsTextAndKeepsValidCrc() {
        val png = TestImages.png(TestImages.text("parameters", "Steps: 20"), TestImages.text("Author", "me"))
        val out = PngRewriter.rewrite(png, { c, _ -> c.type == "tEXt" }, XmpBuilder.disclosurePacket("trainedAlgorithmicMedia"))
        val c = ContainerScanner.scan(out)
        assertTrue(c.textChunks.isEmpty()); assertTrue(c.hasXmp)
        ContainerScanner.pngChunks(out).forEach { ch ->
            val crc = java.util.zip.CRC32().apply { update(out, ch.offset + 4, ch.length + 4) }.value
            assertEquals(ch.type, crc, Bytes.u32be(out, ch.dataStart + ch.length))
        }
    }

    @Test fun instagramCheckerNeverClaimsCertainty() {
        val img = TestImages.jpeg(TestImages.xmpSeg(aiXmp))
        val c = ContainerScanner.scan(img)
        val ai = AiProvenanceScanner.scan(c, noExif, null, XmpParser.parse(c.xmpPackets), C2paReport.NONE)
        val r = InstagramChecker.check(IgInput(ai, ImageFormat.JPEG, 1000, 1080, 1350, true, true, true, true, false, false, null, emptyList()))
        assertEquals(IgAssessment.STRONG, r.assessment)
        assertTrue(r.disclaimer.contains("not a prediction"))
        assertNotNull(r.privacyConcerns.firstOrNull { it.contains("GPS") })
        val clean = InstagramChecker.check(IgInput(AiProvenanceScanner.scan(ContainerScanner.scan(TestImages.jpeg()), noExif, null, null, C2paReport.NONE),
            ImageFormat.JPEG, 1000, 1080, 1350, false, false, false, false, false, false, null, emptyList()))
        assertEquals(IgAssessment.LOW, clean.assessment)
    }

    @Test fun iccDescriptionReadsDescTag() {
        // header(128) + count(1) + tag entry(12) + desc tag
        val text = "sRGB IEC61966-2.1"
        val desc = "desc".toByteArray() + ByteArray(4) + Bytes.be32((text.length + 1).toLong()) + text.toByteArray() + byteArrayOf(0)
        val p = ByteArray(128) + Bytes.be32(1) + "desc".toByteArray() + Bytes.be32(144) + Bytes.be32(desc.size.toLong()) + desc
        assertEquals(text, ContainerScanner.iccDescription(p))
    }
}
