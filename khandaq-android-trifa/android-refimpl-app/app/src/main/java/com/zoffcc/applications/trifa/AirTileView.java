package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign): the square tile at the start of a row. A person shows initials or a photo, a
 * group has a second tile peeking out behind it, a contact who has not answered yet is a dashed
 * outline, saved messages carry a bookmark, and our own tile is filled with ink.
 *
 * <p>The group's back tile is drawn outside the view's bounds, towards the top and the trailing
 * edge, so the parent must not clip its children.
 */
final class AirTileView extends FrameLayout
{
    enum Kind
    {
        PERSON,
        GROUP,
        PENDING,
        /** A group not joined yet, as the command line's "Join the group with this key": dashed, with the group glyph. */
        PENDING_GROUP,
        ME,
        SAVED
    }

    private final ImageView photo;
    private final TextView initials;
    private final ImageView icon;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float hairline;
    private final float backOffset;
    private final DashPathEffect dash;
    private Kind kind = Kind.PERSON;
    private boolean photoShown;

    public AirTileView(final Context context)
    {
        this(context, null);
    }

    public AirTileView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final Resources res = context.getResources();
        radius = res.getDimension(R.dimen.kq_radius_control);
        hairline = res.getDimension(R.dimen.kq_stroke_hairline);
        backOffset = res.getDimension(R.dimen.air_back_tile_offset);
        dash = new DashPathEffect(new float[]{res.getDimension(R.dimen.air_dash_on),
                                              res.getDimension(R.dimen.air_dash_off)}, 0f);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(hairline);

        photo = new ImageView(context);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setOutlineProvider(new ViewOutlineProvider()
        {
            @Override
            public void getOutline(final View view, final Outline outline)
            {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        photo.setClipToOutline(true);
        photo.setVisibility(View.GONE);
        addView(photo, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        initials = new TextView(context);
        TextViewCompat.setTextAppearance(initials, R.style.TextAppearance_KQ_Air_CaptionStrong);
        initials.setGravity(Gravity.CENTER);
        initials.setMaxLines(1);
        initials.setIncludeFontPadding(false);
        final int pad = res.getDimensionPixelSize(R.dimen.kq_space_2);
        initials.setPadding(pad, pad, pad, pad);
        final float caption = initials.getTextSize();
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(initials, Math.max(1, Math.round(caption * 0.7f)),
                                                                   Math.max(2, Math.round(caption)), 1,
                                                                   TypedValue.COMPLEX_UNIT_PX);
        addView(initials, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int iconSize = res.getDimensionPixelSize(R.dimen.kq_size_icon_small);
        addView(icon, new LayoutParams(iconSize, iconSize, Gravity.CENTER));

        bind(Kind.PERSON, "", 0);
    }

    /** Sets what the tile stands for. A photo, if any, is loaded into {@link #photoView()} afterwards. */
    void bind(final Kind next, final String title, final int tone)
    {
        kind = next;
        showPhoto(false);
        final Context context = getContext();
        switch (next)
        {
            case PENDING:
            case PENDING_GROUP:
                initials.setVisibility(View.GONE);
                icon.setVisibility(View.VISIBLE);
                icon.setImageResource(next == Kind.PENDING_GROUP ? R.drawable.kq_ic_users : R.drawable.kq_ic_user_plus);
                ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(
                        AirUi.color(context, R.color.kq_ink_3)));
                break;
            case SAVED:
                initials.setVisibility(View.GONE);
                icon.setVisibility(View.VISIBLE);
                icon.setImageResource(R.drawable.kq_ic_bookmark);
                ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(
                        AirUi.color(context, R.color.kq_ink)));
                break;
            case ME:
                icon.setVisibility(View.GONE);
                initials.setVisibility(View.VISIBLE);
                initials.setText(AirModel.Initials.from(title));
                initials.setTextColor(AirUi.color(context, R.color.kq_on_ink));
                break;
            case GROUP:
            case PERSON:
            default:
                icon.setVisibility(View.GONE);
                initials.setVisibility(View.VISIBLE);
                initials.setText(AirModel.Initials.from(title));
                initials.setTextColor(AirUi.toneColor(context, tone));
                break;
        }
        invalidate();
    }

    ImageView photoView()
    {
        return photo;
    }

    /** A loaded photo covers the initials; without one the initials come back. */
    void showPhoto(final boolean shown)
    {
        photoShown = shown && !pending() && kind != Kind.SAVED;
        photo.setVisibility(photoShown ? View.VISIBLE : View.GONE);
        if (!pending() && kind != Kind.SAVED)
        {
            initials.setVisibility(photoShown ? View.GONE : View.VISIBLE);
        }
        invalidate();
    }

    Kind kind()
    {
        return kind;
    }

    /** Someone or something not answered yet: a dashed outline and no fill. */
    private boolean pending()
    {
        return kind == Kind.PENDING || kind == Kind.PENDING_GROUP;
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        super.onDraw(canvas);
        final Context context = getContext();
        final float inset = hairline / 2f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        if (kind == Kind.GROUP)
        {
            final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            canvas.save();
            canvas.translate(rtl ? -backOffset : backOffset, -backOffset);
            fill.setColor(AirUi.color(context, R.color.kq_surface));
            canvas.drawRoundRect(rect, radius, radius, fill);
            stroke.setPathEffect(null);
            stroke.setColor(AirUi.color(context, R.color.kq_line_strong));
            canvas.drawRoundRect(rect, radius, radius, stroke);
            canvas.restore();
        }

        if (pending())
        {
            return;
        }
        fill.setColor(AirUi.color(context, kind == Kind.ME ? R.color.kq_ink : R.color.kq_sunken));
        canvas.drawRoundRect(rect, radius, radius, fill);
    }

    @Override
    protected void dispatchDraw(final Canvas canvas)
    {
        super.dispatchDraw(canvas);
        // The border goes over the photo too, so a light photo keeps its edge on a light ground.
        if (kind == Kind.ME && !photoShown)
        {
            return;
        }
        final Context context = getContext();
        stroke.setPathEffect(pending() ? dash : null);
        stroke.setColor(AirUi.color(context, pending() ? R.color.kq_line_strong : R.color.kq_line));
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }
}
