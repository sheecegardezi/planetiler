package com.onthegomap.flatmap.openmaptiles.layers;

import static com.onthegomap.flatmap.openmaptiles.Utils.brunnel;
import static com.onthegomap.flatmap.openmaptiles.Utils.coalesce;
import static com.onthegomap.flatmap.openmaptiles.Utils.nullIf;
import static com.onthegomap.flatmap.openmaptiles.Utils.nullIfEmpty;
import static com.onthegomap.flatmap.openmaptiles.layers.Transportation.highwayClass;
import static com.onthegomap.flatmap.openmaptiles.layers.Transportation.highwaySubclass;
import static com.onthegomap.flatmap.openmaptiles.layers.Transportation.isFootwayOrSteps;

import com.graphhopper.reader.ReaderRelation;
import com.onthegomap.flatmap.Arguments;
import com.onthegomap.flatmap.FeatureCollector;
import com.onthegomap.flatmap.FeatureMerge;
import com.onthegomap.flatmap.MemoryEstimator;
import com.onthegomap.flatmap.Parse;
import com.onthegomap.flatmap.SourceFeature;
import com.onthegomap.flatmap.Translations;
import com.onthegomap.flatmap.VectorTileEncoder;
import com.onthegomap.flatmap.ZoomFunction;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.geo.GeometryException;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.openmaptiles.LanguageUtils;
import com.onthegomap.flatmap.openmaptiles.OpenMapTilesProfile;
import com.onthegomap.flatmap.openmaptiles.generated.OpenMapTilesSchema;
import com.onthegomap.flatmap.openmaptiles.generated.Tables;
import com.onthegomap.flatmap.read.OpenStreetMapReader;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransportationName implements
  OpenMapTilesSchema.TransportationName,
  Tables.OsmHighwayLinestring.Handler,
  OpenMapTilesProfile.NaturalEarthProcessor,
  OpenMapTilesProfile.FeaturePostProcessor,
  OpenMapTilesProfile.OsmRelationPreprocessor,
  OpenMapTilesProfile.IgnoreWikidata {

  // extra temp key used to group on/off-ramps separately from main highways
  private static final String LINK_TEMP_KEY = "__islink";
  private static final String RELATION_ID_TEMP_KEY = "__relid";

  private static final Logger LOGGER = LoggerFactory.getLogger(TransportationName.class);
  private static final Pattern GREAT_BRITAIN_REF_NETWORK_PATTERN = Pattern.compile("^[AM][0-9AM()]+");
  private static final Map<String, Integer> MINZOOMS = Map.of(
    FieldValues.CLASS_TRACK, 14,
    FieldValues.CLASS_PATH, 13,
    FieldValues.CLASS_MINOR, 13,
    FieldValues.CLASS_TRUNK, 8,
    FieldValues.CLASS_MOTORWAY, 6
    // default: 12
  );
  private static final ZoomFunction.MeterThresholds MIN_LENGTH = ZoomFunction.meterThresholds()
    .put(6, 20_000)
    .put(7, 20_000)
    .put(8, 14_000)
    .put(9, 8_000)
    .put(10, 8_000)
    .put(11, 8_000);
  private static final double PIXEL = 256d / 4096d;
  private final boolean brunnel;
  private final boolean sizeForShield;
  private final boolean limitMerge;
  private PreparedGeometry greatBritain = null;
  private AtomicBoolean loggedNoGb = new AtomicBoolean(false);

  public TransportationName(Translations translations, Arguments args, Stats stats) {
    this.brunnel = args.get(
      "transportation_name_brunnel",
      "transportation_name layer: set to false to omit brunnel and help merge long highways",
      true
    );
    this.sizeForShield = args.get(
      "transportation_name_size_for_shield",
      "transportation_name layer: allow road names on shorter segments (ie. they will have a shield)",
      false
    );
    this.limitMerge = args.get(
      "transportation_name_limit_merge",
      "transportation_name layer: limit merge so we don't combine different relations to help merge long highways",
      false
    );
  }

  @Override
  public void processNaturalEarth(String table, SourceFeature feature,
    FeatureCollector features) {
    if ("ne_10m_admin_0_countries".equals(table) && feature.hasTag("iso_a2", "GB")) {
      try {
        synchronized (this) {
          Geometry boundary = feature.polygon().buffer(GeoUtils.metersToPixelAtEquator(0, 10_000) / 256d);
          greatBritain = PreparedGeometryFactory.prepare(boundary);
        }
      } catch (GeometryException e) {
        LOGGER.error("Failed to get Great Britain Polygon: " + e);
      }
    }
  }

  @Override
  public List<OpenStreetMapReader.RelationInfo> preprocessOsmRelation(ReaderRelation relation) {
    if (relation.hasTag("route", "road")) {
      RouteNetwork networkType = null;
      String network = relation.getTag("network");
      String name = relation.getTag("name");
      String ref = relation.getTag("ref");

      if ("US:I".equals(network)) {
        networkType = RouteNetwork.US_INTERSTATE;
      } else if ("US:US".equals(network)) {
        networkType = RouteNetwork.US_HIGHWAY;
      } else if (network != null && network.length() == 5 && network.startsWith("US:")) {
        networkType = RouteNetwork.US_STATE;
      } else if (network != null && network.startsWith("CA:transcanada")) {
        networkType = RouteNetwork.CA_TRANSCANADA;
      }

      if (networkType != null) {
        return List.of(new RouteRelation(ref, networkType, relation.getId()));
      }
    }
    return null;
  }

  @Override
  public void process(Tables.OsmHighwayLinestring element, FeatureCollector features) {
    List<OpenStreetMapReader.RelationMember<RouteRelation>> relations = element.source()
      .relationInfo(RouteRelation.class);

    String ref = element.ref();
    RouteRelation relation = getRouteRelation(element, relations, ref);
    if (relation != null && nullIfEmpty(relation.ref) != null) {
      ref = relation.ref;
    }

    String name = nullIfEmpty(element.name());
    ref = nullIfEmpty(ref);
    String highway = nullIfEmpty(element.highway());

    String highwayClass = highwayClass(element.highway(), null, element.construction(), element.manMade());
    if (element.isArea() || highway == null || highwayClass == null || (name == null && ref == null)) {
      return;
    }

    String baseClass = highwayClass.replace("_construction", "");

    int minzoom = MINZOOMS.getOrDefault(baseClass, 12);
    boolean isLink = highway.endsWith("_link");
    if (isLink) {
      minzoom = Math.max(13, minzoom);
    }

    FeatureCollector.Feature feature = features.line(LAYER_NAME)
      .setBufferPixels(BUFFER_SIZE)
      .setBufferPixelOverrides(MIN_LENGTH)
      // TODO abbreviate road names
      .setAttrs(LanguageUtils.getNamesWithoutTranslations(element.source().properties()))
      .setAttr(Fields.REF, ref)
      .setAttr(Fields.REF_LENGTH, ref != null ? ref.length() : null)
      .setAttr(Fields.NETWORK,
        (relation != null && relation.network != null) ? relation.network.name : ref != null ? "road" : null)
      .setAttr(Fields.CLASS, highwayClass)
      .setAttr(Fields.SUBCLASS, highwaySubclass(highwayClass, null, highway))
      .setMinPixelSize(0)
      .setZorder(element.zOrder())
      .setZoomRange(minzoom, 14);

    if (brunnel) {
      feature.setAttr(Fields.BRUNNEL, brunnel(element.isBridge(), element.isTunnel(), element.isFord()));
    }

      /*
       to help group roads into longer segments, add temporary tags to limit which segments get grouped together. Since
       a divided highway typically has a separate relation for each direction, this ends up keeping segments going
       opposite directions group getting grouped together and confusing the line merging process
       */
    if (limitMerge) {
      feature
        .setAttr(LINK_TEMP_KEY, isLink ? 1 : 0)
        .setAttr(RELATION_ID_TEMP_KEY, relation == null ? null : relation.id);
    }

    if (isFootwayOrSteps(highway)) {
      feature
        .setAttrWithMinzoom(Fields.LAYER, nullIf(element.layer(), 0), 12)
        .setAttrWithMinzoom(Fields.LEVEL, Parse.parseLongOrNull(element.source().getTag("level")), 12)
        .setAttrWithMinzoom(Fields.INDOOR, element.indoor() ? 1 : null, 12);
    }
  }

  @Nullable
  private RouteRelation getRouteRelation(Tables.OsmHighwayLinestring element,
    List<OpenStreetMapReader.RelationMember<RouteRelation>> relations, String ref) {
    RouteRelation relation = relations.stream()
      .map(OpenStreetMapReader.RelationMember::relation)
      .min(RELATION_ORDERING)
      .orElse(null);
    if (relation == null && ref != null) {
      Matcher refMatcher = GREAT_BRITAIN_REF_NETWORK_PATTERN.matcher(ref);
      if (refMatcher.find()) {
        if (greatBritain == null) {
          if (!loggedNoGb.get() && loggedNoGb.compareAndSet(false, true)) {
            LOGGER.warn("No GB polygon for inferring route network types");
          }
        } else {
          try {
            Geometry wayGeometry = element.source().worldGeometry();
            if (greatBritain.intersects(wayGeometry)) {
              RouteNetwork networkType =
                "motorway".equals(element.highway()) ? RouteNetwork.GB_MOTORWAY : RouteNetwork.GB_TRUNK;
              relation = new RouteRelation(refMatcher.group(), networkType, 0);
            }
          } catch (GeometryException e) {
            LOGGER.warn("Unable to test highway against GB route network: " + element.source().id());
          }
        }
      }
    }
    return relation;
  }

  @Override
  public List<VectorTileEncoder.Feature> postProcess(int zoom,
    List<VectorTileEncoder.Feature> items) throws GeometryException {
    double tolerance = zoom >= 14 ? PIXEL : 0.1;
    double minLength = coalesce(MIN_LENGTH.apply(zoom), 0).doubleValue();
    // TODO tolerances:
    // z6: (tolerance: 500)
    // z7: (tolerance: 200)
    // z8: (tolerance: 120)
    // z9-11: (tolerance: 50)
    Function<Map<String, Object>, Double> lengthLimitCalculator =
      zoom >= 14 ? (p -> 0d) :
        minLength > 0 ? (p -> minLength) :
          this::getMinLengthForName;
    var result = FeatureMerge.mergeLineStrings(items, lengthLimitCalculator, tolerance, BUFFER_SIZE);
    if (limitMerge) {
      for (var feature : result) {
        feature.attrs().remove(LINK_TEMP_KEY);
        feature.attrs().remove(RELATION_ID_TEMP_KEY);
      }
    }
    return result;
  }

  private double getMinLengthForName(Map<String, Object> attrs) {
    Object ref = attrs.get(Fields.REF);
    Object name = coalesce(attrs.get(Fields.NAME), ref);
    return (sizeForShield && ref instanceof String) ? 6 :
      name instanceof String str ? str.length() * 6 : Double.MAX_VALUE;
  }

  private enum RouteNetwork {

    US_INTERSTATE("us-interstate"),
    US_HIGHWAY("us-highway"),
    US_STATE("us-state"),
    CA_TRANSCANADA("ca-transcanada"),
    GB_MOTORWAY("gb-motorway"),
    GB_TRUNK("gb-trunk");

    final String name;

    RouteNetwork(String name) {
      this.name = name;
    }
  }

  private static record RouteRelation(
    String ref,
    RouteNetwork network,
    long id
  ) implements OpenStreetMapReader.RelationInfo {

    @Override
    public long estimateMemoryUsageBytes() {
      return 24 +
        8 + // network pointer
        8 + // ref pointer
        8 + // id
        MemoryEstimator.size(ref);
    }
  }

  private static final Comparator<RouteRelation> RELATION_ORDERING = Comparator
    .<RouteRelation>comparingInt(r -> r.network.ordinal())
    // TODO also compare network string?
    .thenComparingInt(r -> r.ref == null ? 0 : r.ref.length())
    .thenComparing(r -> r.ref);
}
