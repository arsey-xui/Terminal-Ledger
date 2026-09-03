package com.personal.accounting.android.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.personal.accounting.android.R;

/** 桌面小组件：点击直接弹出快速记账页，无需打开 App 主界面。 */
public class QuickAddWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            Intent intent = new Intent(context, QuickAddWidgetActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_add);
            views.setTextViewText(R.id.widget_title, "快速记账");
            views.setOnClickPendingIntent(R.id.widget_root, pi);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}
