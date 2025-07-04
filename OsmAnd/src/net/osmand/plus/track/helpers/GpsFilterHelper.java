package net.osmand.plus.track.helpers;

import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_MAX_FILTER_ALTITUDE;
import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_MAX_FILTER_HDOP;
import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_MAX_FILTER_SPEED;
import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_MIN_FILTER_ALTITUDE;
import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_MIN_FILTER_SPEED;
import static net.osmand.plus.settings.backend.backup.GpxAppearanceInfo.TAG_SMOOTHING_THRESHOLD;
import static net.osmand.shared.gpx.GpxParameter.MAX_FILTER_ALTITUDE;
import static net.osmand.shared.gpx.GpxParameter.MAX_FILTER_HDOP;
import static net.osmand.shared.gpx.GpxParameter.MAX_FILTER_SPEED;
import static net.osmand.shared.gpx.GpxParameter.MIN_FILTER_ALTITUDE;
import static net.osmand.shared.gpx.GpxParameter.MIN_FILTER_SPEED;
import static net.osmand.shared.gpx.GpxParameter.SMOOTHING_THRESHOLD;

import android.graphics.Typeface;
import android.os.AsyncTask;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.FormattedValue;
import net.osmand.shared.gpx.GpxDataItem;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.GpxTrackAnalysis;
import net.osmand.shared.gpx.primitives.Track;
import net.osmand.shared.gpx.primitives.TrkSegment;
import net.osmand.shared.gpx.primitives.WptPt;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class GpsFilterHelper {

	private final OsmandApplication app;
	private final Set<GpsFilterListener> listeners = new HashSet<>();

	private final Executor singleThreadExecutor = Executors.newSingleThreadExecutor();
	private GpsFilterTask gpsFilterTask;

	public GpsFilterHelper(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void addListener(@NonNull GpsFilterListener listener) {
		listeners.add(listener);
	}

	public void removeListener(@NonNull GpsFilterListener listener) {
		listeners.remove(listener);
	}

	public void clearListeners() {
		listeners.clear();
	}

	public void filterGpxFile(@NonNull FilteredSelectedGpxFile filteredSelectedGpxFile, boolean cancelPrevious) {
		if (cancelPrevious && gpsFilterTask != null) {
			gpsFilterTask.cancel(false);
		}
		gpsFilterTask = new GpsFilterTask(app, filteredSelectedGpxFile, listeners);
		gpsFilterTask.executeOnExecutor(singleThreadExecutor);
	}

	@SuppressWarnings("deprecation")
	private static class GpsFilterTask extends AsyncTask<Void, Void, Boolean> {

		private final OsmandApplication app;
		private final FilteredSelectedGpxFile filteredSelectedGpxFile;
		private final Set<GpsFilterListener> listeners;

		private GpxFile filteredGpxFile;
		private GpxTrackAnalysis trackAnalysis;
		private List<GpxDisplayGroup> displayGroups;

		public GpsFilterTask(@NonNull OsmandApplication app,
		                     @NonNull FilteredSelectedGpxFile filteredGpx,
		                     @NonNull Set<GpsFilterListener> listeners) {
			this.app = app;
			this.filteredSelectedGpxFile = filteredGpx;
			this.listeners = listeners;
		}

		@Override
		protected Boolean doInBackground(Void... voids) {
			SelectedGpxFile sourceSelectedGpxFile = filteredSelectedGpxFile.getSourceSelectedGpxFile();
			GpxFile sourceGpx = sourceSelectedGpxFile.getGpxFile();

			filteredGpxFile = sourceGpx.clone();
			filteredGpxFile.getTracks().clear();

			int analysedPointsCount = 0;
			for (Track track : sourceGpx.getTracks()) {

				Track filteredTrack = new Track();
				filteredTrack.setName(track.getName());
				filteredTrack.setDesc(track.getDesc());

				for (TrkSegment segment : track.getSegments()) {

					if (segment.getGeneralSegment()) {
						continue;
					}

					TrkSegment filteredSegment = new TrkSegment();
					filteredSegment.setName(segment.getName());

					double cumulativeDistance = 0;
					WptPt previousPoint = null;
					List<WptPt> points = segment.getPoints();

					for (int i = 0; i < points.size(); i++) {

						if (isCancelled()) {
							return false;
						}

						WptPt point = points.get(i);

						if (previousPoint != null) {
							cumulativeDistance += MapUtils.getDistance(previousPoint.getLat(), previousPoint.getLon(),
									point.getLat(), point.getLon());
						}
						boolean firstOrLast = i == 0 || i + 1 == points.size();
						boolean singlePoint = points.size() == 1;

						if (acceptPoint(point, analysedPointsCount, cumulativeDistance, firstOrLast, singlePoint)) {
							filteredSegment.getPoints().add(new WptPt(point));
							cumulativeDistance = 0;
						}

						if (!singlePoint) {
							analysedPointsCount++;
						}
						previousPoint = point;
					}

					if (filteredSegment.getPoints().size() != 0) {
						filteredTrack.getSegments().add(filteredSegment);
					}
				}

				if (filteredTrack.getSegments().size() != 0) {
					filteredGpxFile.getTracks().add(filteredTrack);
				}
			}

			if (filteredSelectedGpxFile.isJoinSegments()) {
				filteredGpxFile.addGeneralTrack();
			}

			trackAnalysis = filteredGpxFile.getAnalysis(System.currentTimeMillis());
			displayGroups = processSplit(filteredGpxFile);

			return true;
		}

		private boolean acceptPoint(@NonNull WptPt point, int pointIndex, double cumulativeDistance,
		                            boolean firstOrLast, boolean singlePoint) {
			SpeedFilter speedFilter = filteredSelectedGpxFile.getSpeedFilter();
			AltitudeFilter altitudeFilter = filteredSelectedGpxFile.getAltitudeFilter();
			HdopFilter hdopFilter = filteredSelectedGpxFile.getHdopFilter();
			SmoothingFilter smoothingFilter = filteredSelectedGpxFile.getSmoothingFilter();

			return speedFilter.acceptPoint(point, pointIndex, cumulativeDistance, singlePoint)
					&& altitudeFilter.acceptPoint(point, pointIndex, cumulativeDistance, singlePoint)
					&& hdopFilter.acceptPoint(point, pointIndex, cumulativeDistance, singlePoint)
					&& (firstOrLast || smoothingFilter.acceptPoint(point, pointIndex, cumulativeDistance, singlePoint));
		}

		@Nullable
		private List<GpxDisplayGroup> processSplit(@NonNull GpxFile gpxFile) {
			List<GpxDataItem> dataItems = app.getGpxDbHelper().getSplitItemsBlocking();
			for (GpxDataItem dataItem : dataItems) {
				if (dataItem.getFile().absolutePath().equals(gpxFile.getPath())) {
					return app.getGpxDisplayHelper().processSplitSync(gpxFile, dataItem);
				}
			}
			return null;
		}

		@Override
		protected void onPostExecute(@NonNull Boolean successfulFinish) {
			if (successfulFinish && !isCancelled()) {
				filteredSelectedGpxFile.updateGpxFile(app, filteredGpxFile);
				filteredSelectedGpxFile.setTrackAnalysis(trackAnalysis);
				filteredSelectedGpxFile.setSplitGroups(displayGroups);
				for (GpsFilterListener listener : listeners) {
					listener.onFinishFiltering(filteredGpxFile);
				}
			}
		}
	}

	public abstract static class GpsFilter {
		protected static final int SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE;

		protected GpxTrackAnalysis analysis;

		protected double selectedMinValue;
		protected double selectedMaxValue;

		protected boolean nightMode;

		protected final ForegroundColorSpan blackTextSpan;
		protected final ForegroundColorSpan greyTextSpan;
		protected final StyleSpan boldSpan;

		public GpsFilter(@NonNull OsmandApplication app, @NonNull SelectedGpxFile selectedGpxFile) {
			this.analysis = selectedGpxFile.getTrackAnalysis(app);

			this.selectedMaxValue = getMaxValue();
			if (isRangeSupported()) {
				this.selectedMinValue = getMinValue();
			}

			nightMode = app.getDaynightHelper().isNightMode(ThemeUsageContext.OVER_MAP);

			blackTextSpan = new ForegroundColorSpan(ColorUtilities.getPrimaryTextColor(app, nightMode));
			greyTextSpan = new ForegroundColorSpan(ColorUtilities.getSecondaryTextColor(app, nightMode));
			boldSpan = new StyleSpan(Typeface.BOLD);
		}

		public void updateAnalysis(@NonNull GpxTrackAnalysis analysis) {
			this.analysis = analysis;
			checkSelectedValues();
		}

		protected void checkSelectedValues() {
			if (isRangeSupported()) {
				if (selectedMinValue > selectedMaxValue) {
					double temp = selectedMinValue;
					selectedMinValue = selectedMaxValue;
					selectedMaxValue = temp;
				}
				if (selectedMinValue < getMinValue()) {
					selectedMinValue = getMinValue();
				}
			}
			if (selectedMaxValue > getMaxValue()) {
				selectedMaxValue = getMaxValue();
			}
		}

		public abstract boolean isNeeded();

		public abstract boolean isRangeSupported();

		public abstract boolean acceptPoint(@NonNull WptPt point, int pointIndex,
		                                    double distanceToLastSurvivedPoint, boolean singlePoint);

		public abstract double getMinValue();

		public abstract double getMaxValue();

		public void updateValue(double maxValue) {
			// Not implemented
		}

		public void updateValues(double minValue, double maxValue) {
			// Not implemented
		}

		public void reset() {
			if (isRangeSupported()) {
				updateValues(getMinValue(), getMaxValue());
			} else {
				updateValue(getMaxValue());
			}
		}

		public final double getSelectedMinValue() {
			return isRangeSupported() ? selectedMinValue : getMinValue();
		}

		public final double getSelectedMaxValue() {
			return selectedMaxValue;
		}

		@NonNull
		public CharSequence getFormattedStyledValue(@NonNull OsmandApplication app, double value) {
			String formattedValue = getFormattedValue(value, app);
			int spaceIndex = formattedValue.indexOf(" ");

			int endIndex = spaceIndex == -1 ? formattedValue.length() : spaceIndex;
			SpannableString spannableString = new SpannableString(formattedValue);
			spannableString.setSpan(blackTextSpan, 0, endIndex, SPAN_FLAGS);

			if (spaceIndex != -1) {
				spannableString.setSpan(greyTextSpan, spaceIndex + 1, formattedValue.length(), SPAN_FLAGS);
			}

			return spannableString;
		}

		@NonNull
		public abstract String getFormattedValue(double value, @NonNull OsmandApplication app);

		@NonNull
		public abstract CharSequence getFilterTitle(@NonNull OsmandApplication app);

		@NonNull
		protected CharSequence styleFilterTitle(@NonNull String title, int boldEndIndex) {
			SpannableString spannableTitle = new SpannableString(title);
			spannableTitle.setSpan(blackTextSpan, 0, boldEndIndex, SPAN_FLAGS);
			spannableTitle.setSpan(boldSpan, 0, boldEndIndex, SPAN_FLAGS);
			spannableTitle.setSpan(greyTextSpan, boldEndIndex, title.length(), SPAN_FLAGS);
			return spannableTitle;
		}

		@NonNull
		public abstract String getLeftText(@NonNull OsmandApplication app);

		@NonNull
		public abstract String getRightText(@NonNull OsmandApplication app);

		@StringRes
		public abstract int getDescriptionId();

		protected static double parseValueFromExtensions(@NonNull Map<String, String> gpxExtensions,
		                                                 @NonNull String tag) {
			String value = gpxExtensions.get(tag);
			if (Algorithms.isEmpty(value)) {
				return Double.NaN;
			}
			try {
				return Double.parseDouble(value);
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}

		public static void writeValidFilterValuesToExtensions(@NonNull Map<String, String> gpxExtensions,
		                                                      @NonNull GpxDataItem dataItem) {
			writeValueToExtensionsIfValid(gpxExtensions, TAG_SMOOTHING_THRESHOLD, dataItem.getParameter(SMOOTHING_THRESHOLD));
			writeValueToExtensionsIfValid(gpxExtensions, TAG_MIN_FILTER_SPEED, dataItem.getParameter(MIN_FILTER_SPEED));
			writeValueToExtensionsIfValid(gpxExtensions, TAG_MAX_FILTER_SPEED, dataItem.getParameter(MAX_FILTER_SPEED));
			writeValueToExtensionsIfValid(gpxExtensions, TAG_MIN_FILTER_ALTITUDE, dataItem.getParameter(MIN_FILTER_ALTITUDE));
			writeValueToExtensionsIfValid(gpxExtensions, TAG_MAX_FILTER_ALTITUDE, dataItem.getParameter(MAX_FILTER_ALTITUDE));
			writeValueToExtensionsIfValid(gpxExtensions, TAG_MAX_FILTER_HDOP, dataItem.getParameter(MAX_FILTER_HDOP));
		}

		private static void writeValueToExtensionsIfValid(@NonNull Map<String, String> gpxExtensions,
		                                                  @NonNull String tag, double value) {
			if (!Double.isNaN(value)) {
				gpxExtensions.put(tag, String.valueOf(value));
			}
		}
	}

	public static class SmoothingFilter extends GpsFilter {

		public SmoothingFilter(@NonNull OsmandApplication app, @NonNull SelectedGpxFile selectedGpxFile) {
			super(app, selectedGpxFile);
			selectedMaxValue = getMinValue();
		}

		@Override
		public boolean isNeeded() {
			return true;
		}

		@Override
		public boolean isRangeSupported() {
			return false;
		}

		@Override
		public boolean acceptPoint(@NonNull WptPt point, int pointIndex,
		                           double distanceToLastSurvivedPoint, boolean singlePoint) {
			return !isNeeded() || getSelectedMaxValue() == 0 || distanceToLastSurvivedPoint > getSelectedMaxValue();
		}

		@Override
		public double getMinValue() {
			return 0;
		}

		@Override
		public double getMaxValue() {
			return 100;
		}

		@Override
		public void updateValue(double maxValue) {
			selectedMaxValue = ((int) maxValue);
			checkSelectedValues();
		}

		@Override
		public void reset() {
			updateValue(getMinValue());
		}

		@NonNull
		@Override
		public String getFormattedValue(double value, @NonNull OsmandApplication app) {
			return OsmAndFormatter.getFormattedDistance((float) value, app);
		}

		@NonNull
		@Override
		public CharSequence getFilterTitle(@NonNull OsmandApplication app) {
			String smoothing = app.getString(R.string.gps_filter_smoothing);
			String title = app.getString(R.string.ltr_or_rtl_combine_via_colon, smoothing,
					getFormattedValue(getSelectedMaxValue(), app));
			return styleFilterTitle(title, smoothing.length() + 1);
		}

		@NonNull
		@Override
		public String getLeftText(@NonNull OsmandApplication app) {
			return app.getString(R.string.distance_between_points);
		}

		@NonNull
		@Override
		public String getRightText(@NonNull OsmandApplication app) {
			return getFormattedValue(getSelectedMaxValue(), app);
		}

		@Override
		public int getDescriptionId() {
			return R.string.gps_filter_smoothing_desc;
		}

		public static double getSmoothingThreshold(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_SMOOTHING_THRESHOLD);
		}
	}

	public static class SpeedFilter extends GpsFilter {

		public SpeedFilter(@NonNull OsmandApplication app, @NonNull SelectedGpxFile selectedGpxFile) {
			super(app, selectedGpxFile);
		}

		@Override
		public boolean isNeeded() {
			return analysis.isSpeedSpecified();
		}

		@Override
		public boolean isRangeSupported() {
			return true;
		}

		@Override
		public boolean acceptPoint(@NonNull WptPt point, int pointIndex,
		                           double distanceToLastSurvivedPoint, boolean singlePoint) {
			float speed = singlePoint ? (float) point.getSpeed() : analysis.getPointAttributes().get(pointIndex).getSpeed();
			return !isNeeded() || getSelectedMinValue() <= speed && speed <= getSelectedMaxValue();
		}

		@Override
		public double getMinValue() {
			return 0d;
		}

		@Override
		public double getMaxValue() {
			return Math.ceil(analysis.getMaxSpeed());
		}

		@Override
		public void updateValues(double minValue, double maxValue) {
			selectedMinValue = minValue;
			selectedMaxValue = maxValue;
			checkSelectedValues();
		}

		@NonNull
		@Override
		public String getFormattedValue(double value, @NonNull OsmandApplication app) {
			return OsmAndFormatter.getFormattedSpeed((float) value, app);
		}

		@NonNull
		@Override
		public CharSequence getFilterTitle(@NonNull OsmandApplication app) {
			String speed = app.getString(R.string.shared_string_speed);
			String titleContent;
			if (!isNeeded()) {
				titleContent = app.getString(R.string.gpx_logging_no_data);
			} else {
				FormattedValue min = OsmAndFormatter.getFormattedSpeedValue((float) getSelectedMinValue(), app);
				FormattedValue max = OsmAndFormatter.getFormattedSpeedValue((float) getSelectedMaxValue(), app);
				if (min.unit.equals(max.unit)) {
					String range = app.getString(R.string.ltr_or_rtl_combine_via_dash, min.value, max.value);
					titleContent = app.getString(R.string.ltr_or_rtl_combine_via_space, range, min.unit);
				} else {
					String minFormatted = min.format(app);
					String maxFormatted = max.format(app);
					titleContent = app.getString(R.string.ltr_or_rtl_combine_via_dash, minFormatted, maxFormatted);
				}
			}
			String title = app.getString(R.string.ltr_or_rtl_combine_via_colon, speed, titleContent);

			return styleFilterTitle(title, speed.length() + 1);
		}

		@NonNull
		@Override
		public String getLeftText(@NonNull OsmandApplication app) {
			return app.getString(R.string.shared_string_min);
		}

		@NonNull
		@Override
		public String getRightText(@NonNull OsmandApplication app) {
			return app.getString(R.string.shared_string_max);
		}

		@Override
		public int getDescriptionId() {
			return R.string.gps_filter_speed_altitude_desc;
		}

		public static double getMinFilterSpeed(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_MIN_FILTER_SPEED);
		}

		public static double getMaxFilterSpeed(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_MAX_FILTER_SPEED);
		}
	}

	public static class AltitudeFilter extends GpsFilter {

		public AltitudeFilter(@NonNull OsmandApplication app, @NonNull SelectedGpxFile selectedGpxFile) {
			super(app, selectedGpxFile);
		}

		@Override
		public boolean isNeeded() {
			return analysis.isElevationSpecified();
		}

		@Override
		public boolean isRangeSupported() {
			return true;
		}

		@Override
		public boolean acceptPoint(@NonNull WptPt point, int pointIndex,
		                           double distanceToLastSurvivedPoint, boolean singlePoint) {
			float altitude = singlePoint ? (float) point.getEle() : analysis.getPointAttributes().get(pointIndex).getElevation();
			return !isNeeded() || getSelectedMinValue() <= altitude && altitude <= getSelectedMaxValue();
		}

		@Override
		public double getMinValue() {
			return ((int) Math.floor(analysis.getMinElevation()));
		}

		@Override
		public double getMaxValue() {
			return ((int) Math.ceil(analysis.getMaxElevation()));
		}

		@Override
		public void updateValues(double minValue, double maxValue) {
			selectedMinValue = ((int) minValue);
			selectedMaxValue = ((int) maxValue);
			checkSelectedValues();
		}

		@NonNull
		@Override
		public String getFormattedValue(double value, @NonNull OsmandApplication app) {
			return OsmAndFormatter.getFormattedAlt(value, app);
		}

		@NonNull
		@Override
		public CharSequence getFilterTitle(@NonNull OsmandApplication app) {
			String altitude = app.getString(R.string.altitude);
			String value;
			if (!isNeeded()) {
				value = app.getString(R.string.gpx_logging_no_data);
			} else {
				String minAltitude = getFormattedValue(getSelectedMinValue(), app);
				String maxAltitude = getFormattedValue(getSelectedMaxValue(), app);
				value = app.getString(R.string.ltr_or_rtl_combine_via_dash, minAltitude, maxAltitude);
			}
			String title = app.getString(R.string.ltr_or_rtl_combine_via_colon, altitude, value);

			return styleFilterTitle(title, altitude.length());
		}

		@NonNull
		@Override
		public String getLeftText(@NonNull OsmandApplication app) {
			return app.getString(R.string.shared_string_min);
		}

		@NonNull
		@Override
		public String getRightText(@NonNull OsmandApplication app) {
			return app.getString(R.string.shared_string_max);
		}

		@Override
		public int getDescriptionId() {
			return R.string.gps_filter_speed_altitude_desc;
		}

		public static double getMinFilterAltitude(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_MIN_FILTER_ALTITUDE);
		}

		public static double getMaxFilterAltitude(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_MAX_FILTER_ALTITUDE);
		}
	}

	public static class HdopFilter extends GpsFilter {

		public HdopFilter(@NonNull OsmandApplication app, @NonNull SelectedGpxFile selectedGpxFile) {
			super(app, selectedGpxFile);
		}

		@Override
		public boolean isNeeded() {
			return analysis.isHdopSpecified();
		}

		@Override
		public boolean isRangeSupported() {
			return false;
		}

		@Override
		public boolean acceptPoint(@NonNull WptPt point, int pointIndex,
		                           double distanceToLastSurvivedPoint, boolean singlePoint) {
			return !isNeeded() || point.getHdop() <= getSelectedMaxValue();
		}

		@Override
		public double getMinValue() {
			return Math.floor(analysis.getMinHdop());
		}

		@Override
		public double getMaxValue() {
			return Math.ceil(analysis.getMaxHdop());
		}

		@Override
		public void updateValue(double maxValue) {
			selectedMaxValue = maxValue;
			checkSelectedValues();
		}

		@NonNull
		@Override
		public String getFormattedValue(double value, @NonNull OsmandApplication app) {
			return OsmAndFormatter.getFormattedDistance(((float) value), app);
		}

		@NonNull
		@Override
		public CharSequence getFilterTitle(@NonNull OsmandApplication app) {
			String gpsPrecision = app.getString(R.string.gps_filter_precision);
			String value;
			if (isNeeded()) {
				String minHdop = getFormattedValue(getMinValue(), app);
				String maxHdop = getFormattedValue(getSelectedMaxValue(), app);
				value = app.getString(R.string.ltr_or_rtl_combine_via_dash, minHdop, maxHdop);
			} else {
				value = app.getString(R.string.gpx_logging_no_data);
			}
			String title = app.getString(R.string.ltr_or_rtl_combine_via_colon, gpsPrecision, value);
			return styleFilterTitle(title, gpsPrecision.length() + 1);
		}

		@NonNull
		@Override
		public String getLeftText(@NonNull OsmandApplication app) {
			return app.getString(R.string.shared_string_precision);
		}

		@NonNull
		@Override
		public String getRightText(@NonNull OsmandApplication app) {
			return getFormattedValue(getSelectedMaxValue(), app);
		}

		@Override
		public int getDescriptionId() {
			return R.string.gps_filter_hdop_desc;
		}

		public static double getMaxFilterHdop(@NonNull Map<String, String> gpxExtensions) {
			return parseValueFromExtensions(gpxExtensions, TAG_MAX_FILTER_HDOP);
		}
	}

	public interface GpsFilterListener {

		void onFinishFiltering(@NonNull GpxFile filteredGpxFile);
	}
}