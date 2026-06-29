package com.zoffcc.applications.trifa;

import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.khandaq.messenger.R;

/**
 * KHANDAQ onboarding (Figma "Онбординг"): 3 intro slides. Swipe or the primary button advances;
 * on the last slide the primary button becomes "Создать аккаунт" and a secondary "Импорт профиля"
 * appears. Both terminal actions route into SetPasswordActivity (import auto-starts the picker there).
 */
public class OnboardingActivity extends AppCompatActivity
{
    private static final int[] ILLUSTRATIONS = {
            R.drawable.welcome_illu_nophone,
            R.drawable.welcome_illu_encryption,
            R.drawable.welcome_illu_private,
    };
    private static final int[] TITLES = {
            R.string.onboarding_title_1,
            R.string.onboarding_title_2,
            R.string.onboarding_title_3,
    };
    private static final int[] DESCS = {
            R.string.onboarding_desc_1,
            R.string.onboarding_desc_2,
            R.string.onboarding_desc_3,
    };

    private int index = 0;

    private ImageView illustration;
    private TextView title;
    private TextView desc;
    private TextView btnPrimary;
    private TextView btnSecondary;
    private View[] dots;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        illustration = findViewById(R.id.onboarding_illustration);
        title = findViewById(R.id.onboarding_title);
        desc = findViewById(R.id.onboarding_desc);
        btnPrimary = findViewById(R.id.onboarding_btn_primary);
        btnSecondary = findViewById(R.id.onboarding_btn_secondary);
        dots = new View[]{
                findViewById(R.id.onboarding_dot_0),
                findViewById(R.id.onboarding_dot_1),
                findViewById(R.id.onboarding_dot_2),
        };

        btnPrimary.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (index < ILLUSTRATIONS.length - 1)
                {
                    showSlide(index + 1);
                }
                else
                {
                    goToSetup(false);
                }
            }
        });

        btnSecondary.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                goToSetup(true);
            }
        });

        final GestureDetector gesture = new GestureDetector(this, new SwipeListener());
        View root = findViewById(R.id.onboarding_root);
        root.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                return gesture.onTouchEvent(event);
            }
        });

        showSlide(0);
    }

    private void showSlide(int newIndex)
    {
        index = newIndex;
        illustration.setImageResource(ILLUSTRATIONS[index]);
        title.setText(TITLES[index]);
        desc.setText(DESCS[index]);

        for (int i = 0; i < dots.length; i++)
        {
            dots[i].setBackgroundResource(i <= index
                    ? R.drawable.bg_onboarding_dot_active
                    : R.drawable.bg_onboarding_dot_track);
        }

        boolean last = index == ILLUSTRATIONS.length - 1;
        btnPrimary.setText(last ? R.string.onboarding_create_account : R.string.onboarding_next);
        btnSecondary.setVisibility(last ? View.VISIBLE : View.GONE);
    }

    private void goToSetup(boolean startImport)
    {
        Intent intent = startImport
                ? new Intent(this, ImportProfileInfoActivity.class)
                : new Intent(this, SetPasswordActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed()
    {
        if (index > 0)
        {
            showSlide(index - 1);
        }
        else
        {
            super.onBackPressed();
        }
    }

    private final class SwipeListener extends GestureDetector.SimpleOnGestureListener
    {
        private static final int THRESHOLD = 80;
        private static final int VELOCITY = 100;

        @Override
        public boolean onDown(MotionEvent e)
        {
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY)
        {
            if (e1 == null || e2 == null)
            {
                return false;
            }
            float dx = e2.getX() - e1.getX();
            if (Math.abs(dx) > THRESHOLD && Math.abs(velocityX) > VELOCITY)
            {
                if (dx < 0)
                {
                    if (index < ILLUSTRATIONS.length - 1)
                    {
                        showSlide(index + 1);
                    }
                }
                else
                {
                    if (index > 0)
                    {
                        showSlide(index - 1);
                    }
                }
                return true;
            }
            return false;
        }
    }
}
