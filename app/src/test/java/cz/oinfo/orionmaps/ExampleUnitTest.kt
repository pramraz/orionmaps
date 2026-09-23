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
}