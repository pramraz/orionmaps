package cz.oinfo.orionmaps

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun georeferenceBoundingBoxCheck() {
        val geoNorth = 50.1
        val geoSouth = 50.0
        val geoEast = 15.1
        val geoWest = 15.0

        fun isInside(lat: Double, lon: Double): Boolean {
            return lat <= geoNorth && lat >= geoSouth && lon <= geoEast && lon >= geoWest
        }

        assertTrue(isInside(50.05, 15.05))
        assertFalse(isInside(51.0, 15.05))
        assertFalse(isInside(49.9, 15.05))
        assertFalse(isInside(50.05, 16.0))
    }

    @Test
    fun formatMapNameTest() {
        fun formatMapName(name: String): String {
            if (name.length <= 60) return name

            val dotIndex = name.lastIndexOf('.')
            if (dotIndex == -1 || dotIndex == 0 || dotIndex == name.length - 1) {
                return name.take(57) + "..."
            }

            val ext = name.substring(dotIndex)
            val stem = name.substring(0, dotIndex)

            val maxStemLength = 60 - 3 - ext.length
            if (maxStemLength <= 0) {
                return name.take(57) + "..."
            }

            return stem.take(maxStemLength) + "..." + ext
        }

        // Short name
        assertEquals("map.kmz", formatMapName("map.kmz"))

        // Exact 60 chars
        val exact60 = "12345678901234567890123456789012345678901234567890123456.kmz"
        assertEquals(60, exact60.length)
        assertEquals(exact60, formatMapName(exact60))

        // Over 60 chars
        val longKmz = "a_very_long_map_filename_that_exceeds_sixty_characters_in_total_length.kmz"
        val formattedKmz = formatMapName(longKmz)
        assertEquals(60, formattedKmz.length)
        assertTrue(formattedKmz.endsWith("...kmz"))

        val longPdf = "very_long_pdf_file_name_exceeding_sixty_characters_limit_test.pdf"
        val formattedPdf = formatMapName(longPdf)
        assertEquals(60, formattedPdf.length)
        assertTrue(formattedPdf.endsWith("...pdf"))
    }

    @Test
    fun suggestedGpxFilenameSanitizationTest() {
        fun sanitizeName(mapName: String): String {
            val stem = mapName.substringBeforeLast('.', mapName)
            val normalized = java.text.Normalizer.normalize(stem, java.text.Normalizer.Form.NFD)
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            return normalized
                .trim()
                .lowercase(java.util.Locale.US)
                .replace("[^a-z0-9_-]+".toRegex(), "-")
                .trim('-')
                .ifEmpty { "map" }
        }

        assertEquals("moje-mapa", sanitizeName("Moje Mapa.kmz"))
        assertEquals("prilis-zlutoucky-kun_ob2026", sanitizeName("Příliš žluťoučký kůň_OB2026.pdf"))
        assertEquals("map", sanitizeName("!!!.kmz"))
    }

    @Test
    fun kmlGroundOverlayParsingTest() {
        fun extractCoord(kml: String, tag: String): Double? {
            val regex = "<$tag\\b[^>]*>\\s*([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*</$tag>".toRegex()
            return regex.find(kml)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        fun parseOverlays(kml: String): List<String> {
            val overlays = mutableListOf<String>()
            val overlayRegex = "<GroundOverlay\\b[^>]*>([\\s\\S]*?)</GroundOverlay>".toRegex()
            val hrefRegex = "<href\\b[^>]*>([\\s\\S]*?)</href>".toRegex()

            overlayRegex.findAll(kml).forEach { match ->
                val content = match.groupValues[1]
                val href = hrefRegex.find(content)?.groupValues?.get(1)?.trim()?.replace("\\", "/") ?: ""
                val north = extractCoord(content, "north")
                if (href.isNotEmpty() && north != null) {
                    overlays.add("$href:$north")
                }
            }
            return overlays
        }

        val ocadKml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Folder>
                <GroundOverlay>
                    <name>tile_0_0.jpg</name>
                    <Icon><href>files/tile_0_0.jpg</href></Icon>
                    <LatLonBox><north>50.043578844</north></LatLonBox>
                </GroundOverlay>
            </Folder>
            </kml>
        """.trimIndent()

        val ooMapperKml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
            <Folder>
             <GroundOverlay id="tile_0_0.jpg">
              <name>tile_0_0.jpg</name>
              <Icon><href>files/tile_0_0.jpg</href></Icon>
              <LatLonBox><north>50.05498943093903</north></LatLonBox>
             </GroundOverlay>
            </Folder>
            </kml>
        """.trimIndent()

        val ocadParsed = parseOverlays(ocadKml)
        assertEquals(1, ocadParsed.size)
        assertEquals("files/tile_0_0.jpg:50.043578844", ocadParsed[0])

        val ooMapperParsed = parseOverlays(ooMapperKml)
        assertEquals(1, ooMapperParsed.size)
        assertEquals("files/tile_0_0.jpg:50.05498943093903", ooMapperParsed[0])
    }

    @Test
    fun pdfGeoreferenceExtractionTest() {
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [ 0 0 595.28 841.89 ] /VP [ << /Subtype /GEO /BBox [ 0 0 595.28 841.89 ] /GPTS [ 49.0 14.0 50.0 14.0 50.0 15.0 49.0 15.0 ] /LPTS [ 0 0 0 1 1 1 1 0 ] >> ] >>
            endobj
            xref
            0 4
            0000000000 65535 f 
            0000000009 00000 n 
            0000000058 00000 n 
            0000000115 00000 n 
            trailer
            << /Size 4 /Root 1 0 R >>
            startxref
            300
            %%EOF
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("geopdf_test", ".pdf")
        try {
            tempFile.writeBytes(pdfContent.toByteArray(Charsets.ISO_8859_1))

            val dummyViewModel = cz.oinfo.orionmaps.ui.MapViewModel(android.app.Application())
            val method = cz.oinfo.orionmaps.ui.MapViewModel::class.java.getDeclaredMethod("extractPdfGeoreference", java.io.File::class.java)
            method.isAccessible = true

            val geo = method.invoke(dummyViewModel, tempFile) as cz.oinfo.orionmaps.ui.MapGeoreference?
            assertNotNull(geo)

            assertEquals(50.0, geo!!.north, 1e-4)
            assertEquals(49.0, geo.south, 1e-4)
            assertEquals(15.0, geo.east, 1e-4)
            assertEquals(14.0, geo.west, 1e-4)
            assertEquals(0.0, geo.rotation, 1e-4)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun pdfGeoreferenceFlateDecodeTest() {
        val vpBlock = "/VP [ << /Subtype /GEO /BBox [ 0 0 100 200 ] /GPTS [ 49.0 14.0 50.0 14.0 50.0 15.0 49.0 15.0 ] /LPTS [ 0 0 0 1 1 1 1 0 ] >> ]"
        val deflater = java.util.zip.Deflater()
        deflater.setInput(vpBlock.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            baos.write(buf, 0, count)
        }
        deflater.end()
        val compressedBytes = baos.toByteArray()

        val pdfHeader = "%PDF-1.4\n/MediaBox [ 0 0 100 200 ]\n/Filter /FlateDecode\nstream\r\n"
        val pdfFooter = "\r\nendstream\n%%EOF"

        val pdfFileBytes = pdfHeader.toByteArray(Charsets.ISO_8859_1) + compressedBytes + pdfFooter.toByteArray(Charsets.ISO_8859_1)

        val tempFile = java.io.File.createTempFile("geopdf_flate_test", ".pdf")
        try {
            tempFile.writeBytes(pdfFileBytes)

            val dummyViewModel = cz.oinfo.orionmaps.ui.MapViewModel(android.app.Application())
            val method = cz.oinfo.orionmaps.ui.MapViewModel::class.java.getDeclaredMethod("extractPdfGeoreference", java.io.File::class.java)
            method.isAccessible = true

            val geo = method.invoke(dummyViewModel, tempFile) as cz.oinfo.orionmaps.ui.MapGeoreference?
            assertNotNull(geo)

            assertEquals(50.0, geo!!.north, 1e-4)
            assertEquals(49.0, geo.south, 1e-4)
            assertEquals(15.0, geo.east, 1e-4)
            assertEquals(14.0, geo.west, 1e-4)
        } finally {
            tempFile.delete()
        }
    }
}