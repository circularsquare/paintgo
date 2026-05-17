package com.anita.paintgo.regions

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

enum class RegionKind { COUNTRY, STATE, CITY, NEIGHBORHOOD }

class Region(
    val sourceKey: String,
    val externalId: String,
    val kind: RegionKind,
    val name: String,
    val geometry: Geometry,
    val areaM2: Double,
    val bboxS: Double,
    val bboxN: Double,
    val bboxW: Double,
    val bboxE: Double,
) {
    val key: String get() = VisitedRegions.key(sourceKey, externalId)
    val prepared: PreparedGeometry by lazy(LazyThreadSafetyMode.PUBLICATION) {
        PreparedGeometryFactory.prepare(geometry)
    }
}

data class RegionStat(
    val key: String,
    val kind: RegionKind,
    val name: String,
    val percentCovered: Double,
)
