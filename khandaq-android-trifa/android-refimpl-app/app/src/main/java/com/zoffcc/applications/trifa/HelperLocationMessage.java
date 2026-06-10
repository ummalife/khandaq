package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;

public final class HelperLocationMessage
{
    private static final String TAG_MAP = "khandaq_location_map";
    private static final Pattern KHANDAQ_LOCATION = Pattern.compile(
            "khandaq-location:([-\\d.]+),([-\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_LOCATION = Pattern.compile(
            "my\\s+Location:\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)", Pattern.CASE_INSENSITIVE);

    private HelperLocationMessage()
    {
    }

    public static double[] parse(String text)
    {
        if (text == null)
        {
            return null;
        }

        Matcher matcher = KHANDAQ_LOCATION.matcher(text.trim());
        if (matcher.find())
        {
            return parsePair(matcher.group(1), matcher.group(2));
        }

        matcher = LEGACY_LOCATION.matcher(text);
        if (matcher.find())
        {
            return parsePair(matcher.group(1), matcher.group(2));
        }

        return null;
    }

    private static double[] parsePair(String latString, String lonString)
    {
        try
        {
            return new double[]{Double.parseDouble(latString), Double.parseDouble(lonString)};
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    public static boolean bind(View itemView, TextView textView, String rawText)
    {
        return bind(itemView, textView, rawText, true);
    }

    public static boolean bind(View itemView, TextView textView, String rawText, boolean autoLoadMap)
    {
        final double[] coordinates = parse(rawText);
        final ViewGroup bubbleContainer = findBubbleContainer(itemView);

        if (coordinates == null)
        {
            hideMap(itemView, bubbleContainer);
            return false;
        }

        textView.setText(String.format(Locale.US, "%.5f, %.5f", coordinates[0], coordinates[1]));

        if (bubbleContainer == null)
        {
            return true;
        }

        final ImageView mapView = ensureMapView(itemView, bubbleContainer);
        mapView.setVisibility(View.VISIBLE);
        mapView.setOnClickListener(v -> {
            if (mapView.getDrawable() == null)
            {
                final String cacheKey = String.format(Locale.US, "%.5f,%.5f", coordinates[0], coordinates[1]);
                mapView.setTag(cacheKey);
                LocationMapSnapshot.loadInto(mapView, coordinates[0], coordinates[1]);
            }
            openMaps(v.getContext(), coordinates[0], coordinates[1]);
        });

        if (autoLoadMap)
        {
            final String cacheKey = String.format(Locale.US, "%.5f,%.5f", coordinates[0], coordinates[1]);
            mapView.setTag(cacheKey);
            LocationMapSnapshot.loadInto(mapView, coordinates[0], coordinates[1]);
        }
        else
        {
            mapView.setImageDrawable(null);
        }

        return true;
    }

    private static ViewGroup findBubbleContainer(View itemView)
    {
        ViewGroup bubbleContainer = itemView.findViewById(org.khandaq.messenger.R.id.m_container);
        if (bubbleContainer != null)
        {
            return bubbleContainer;
        }

        return itemView.findViewById(org.khandaq.messenger.R.id.text_block_group);
    }

    private static boolean isIncomingBubble(ViewGroup bubbleContainer)
    {
        return bubbleContainer.getId() == org.khandaq.messenger.R.id.m_container;
    }

    private static ViewGroup mapHost(View itemView, ViewGroup bubbleContainer)
    {
        if (isIncomingBubble(bubbleContainer))
        {
            return itemView.findViewById(org.khandaq.messenger.R.id.layout_message_container);
        }

        return bubbleContainer;
    }

    private static ImageView findMapView(ViewGroup host)
    {
        if (host == null)
        {
            return null;
        }

        for (int i = 0; i < host.getChildCount(); i++)
        {
            final View child = host.getChildAt(i);
            if (child instanceof ImageView && TAG_MAP.equals(child.getTag()))
            {
                return (ImageView) child;
            }
        }

        return null;
    }

    private static ImageView ensureMapView(View itemView, ViewGroup bubbleContainer)
    {
        final ViewGroup host = mapHost(itemView, bubbleContainer);
        ImageView mapView = findMapView(host);

        if (mapView != null)
        {
            return mapView;
        }

        final Context context = bubbleContainer.getContext();
        mapView = new ImageView(context);
        mapView.setTag(TAG_MAP);
        mapView.setAdjustViewBounds(true);
        mapView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        final int mapHeight = (int) dp2px(140);
        final ViewGroup.LayoutParams mapParams;

        if (isIncomingBubble(bubbleContainer))
        {
            final LinearLayout.LayoutParams incomingParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, mapHeight);
            incomingParams.leftMargin = (int) dp2px(65);
            incomingParams.rightMargin = (int) dp2px(20);
            incomingParams.bottomMargin = (int) dp2px(4);
            mapParams = incomingParams;

            final View layout2 = itemView.findViewById(org.khandaq.messenger.R.id.layout2);
            final int insertIndex = layout2 != null ? host.indexOfChild(layout2) : host.getChildCount();
            host.addView(mapView, insertIndex, mapParams);
        }
        else if (bubbleContainer instanceof LinearLayout)
        {
            final LinearLayout outgoingBubble = (LinearLayout) bubbleContainer;
            outgoingBubble.setOrientation(LinearLayout.VERTICAL);
            mapParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mapHeight);
            ((LinearLayout.LayoutParams) mapParams).bottomMargin = (int) dp2px(4);
            outgoingBubble.addView(mapView, 0, mapParams);
        }
        else
        {
            mapParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mapHeight);
            bubbleContainer.addView(mapView, 0, mapParams);
        }

        return mapView;
    }

    private static void hideMap(View itemView, ViewGroup bubbleContainer)
    {
        final ViewGroup incomingHost = itemView.findViewById(org.khandaq.messenger.R.id.layout_message_container);
        hideMapInHost(incomingHost);

        if (bubbleContainer != null && !isIncomingBubble(bubbleContainer))
        {
            hideMapInHost(bubbleContainer);
        }
    }

    private static void hideMapInHost(ViewGroup host)
    {
        if (host == null)
        {
            return;
        }

        final ImageView mapView = findMapView(host);
        if (mapView != null)
        {
            mapView.setVisibility(View.GONE);
            mapView.setImageDrawable(null);
            mapView.setTag(null);
        }
    }

    public static void openMaps(Context context, double latitude, double longitude)
    {
        final Uri geoUri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f",
                                                   latitude, longitude, latitude, longitude));
        final Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try
        {
            context.startActivity(intent);
        }
        catch (Exception ignored)
        {
            final Uri webUri = Uri.parse(String.format(Locale.US,
                                                       "https://www.openstreetmap.org/?mlat=%f&mlon=%f#map=15/%f/%f",
                                                       latitude, longitude, latitude, longitude));
            context.startActivity(new Intent(Intent.ACTION_VIEW, webUri));
        }
    }
}
