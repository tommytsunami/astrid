package com.todoroo.astrid.widget;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews;

import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.activity.TaskEditActivity;
import com.todoroo.astrid.activity.TaskEditFragment;
import com.todoroo.astrid.activity.TaskListActivity;
import com.todoroo.astrid.activity.TaskListFragment;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.FilterWithCustomIntent;
import com.todoroo.astrid.api.PermaSql;
import com.todoroo.astrid.core.CoreFilterExposer;
import com.todoroo.astrid.core.SortHelper;
import com.todoroo.astrid.dao.Database;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.service.TaskService;
import com.todoroo.astrid.service.ThemeService;
import com.todoroo.astrid.utility.AstridPreferences;
import com.todoroo.astrid.utility.Constants;

public class TasksWidget extends AppWidgetProvider {

    public static final int THEME_LEGACY = -1;

    static {
        AstridDependencyInjector.initialize();
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
            int[] appWidgetIds) {

        try {
            ContextManager.setContext(context);
            super.onUpdate(context, appWidgetManager, appWidgetIds);

            // Start in service to prevent Application Not Responding timeout
            updateWidgets(context);
        } catch (Exception e) {
            Log.e("astrid-update-widget", "widget update error", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Update all widgets
     * @param id
     */
    public static void updateWidgets(Context context) {
        context.startService(new Intent(context,
                TasksWidget.WidgetUpdateService.class));
    }

    /**
     * Update widget with the given id
     * @param id
     */
    public static void updateWidget(Context context, int id) {
        Intent intent = new Intent(ContextManager.getContext(),
                TasksWidget.WidgetUpdateService.class);
        intent.putExtra(WidgetUpdateService.EXTRA_WIDGET_ID, id);
        context.startService(intent);
    }

    public static class ConfigActivity extends WidgetConfigActivity {
        @Override
        public void updateWidget() {
            TasksWidget.updateWidget(this, mAppWidgetId);
        }
    }

    public static class WidgetUpdateService extends Service {

        public static final String EXTRA_WIDGET_ID = "widget_id"; //$NON-NLS-1$

        @Autowired
        Database database;

        @Autowired
        TaskService taskService;

        @Override
        public void onStart(final Intent intent, int startId) {
            ContextManager.setContext(this);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    startServiceInBackgroundThread(intent);
                }
            }).start();
        }

        public void startServiceInBackgroundThread(Intent intent) {
            ComponentName thisWidget = new ComponentName(this,
                    TasksWidget.class);
            AppWidgetManager manager = AppWidgetManager.getInstance(this);

            int extrasId = AppWidgetManager.INVALID_APPWIDGET_ID;
            if(intent != null)
                extrasId = intent.getIntExtra(EXTRA_WIDGET_ID, extrasId);
            if(extrasId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                for(int id : manager.getAppWidgetIds(thisWidget)) {
                    RemoteViews updateViews = buildUpdate(this, id);
                    manager.updateAppWidget(id, updateViews);
                }
            } else {
                int id = extrasId;
                RemoteViews updateViews = buildUpdate(this, id);
                manager.updateAppWidget(id, updateViews);
            }

            stopSelf();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @SuppressWarnings("nls")
        public RemoteViews buildUpdate(Context context, int widgetId) {
            DependencyInjectionService.getInstance().inject(this);

            RemoteViews views = getThemedRemoteViews(context);

            int numberOfTasks = getNumberOfTasks();

            TodorooCursor<Task> cursor = null;
            Filter filter = null;
            try {
                filter = getFilter(widgetId);
                views.setTextViewText(R.id.widget_title, filter.title);
                views.removeAllViews(R.id.taskbody);

                SharedPreferences publicPrefs = AstridPreferences.getPublicPrefs(this);
                int flags = publicPrefs.getInt(SortHelper.PREF_SORT_FLAGS, 0);
                int sort = publicPrefs.getInt(SortHelper.PREF_SORT_SORT, 0);
                String query = SortHelper.adjustQueryForFlagsAndSort(
                        filter.sqlQuery, flags, sort).replaceAll("LIMIT \\d+", "") + " LIMIT " + numberOfTasks;

                database.openForReading();
                cursor = taskService.fetchFiltered(query, null, Task.ID, Task.TITLE, Task.DUE_DATE, Task.COMPLETION_DATE);
                Task task = new Task();
                int i = 0;
                for (i = 0; i < cursor.getCount() && i < numberOfTasks; i++) {
                    cursor.moveToPosition(i);
                    task.readFromCursor(cursor);

                    String textContent = "";
                    Resources r = context.getResources();
                    int textColor = r
                            .getColor(isDarkTheme() ? R.color.widget_text_color_dark : R.color.widget_text_color_light);
                    if (isLegacyTheme())
                        textColor = r.getColor(android.R.color.white);

                    textContent = task.getValue(Task.TITLE);

                    if(task.isCompleted())
                        textColor = r.getColor(R.color.task_list_done);
                    else if(task.hasDueDate() && task.getValue(Task.DUE_DATE) < DateUtilities.now())
                        textColor = r.getColor(R.color.task_list_overdue);

                    RemoteViews row = new RemoteViews(Constants.PACKAGE, R.layout.widget_row);

                    row.setTextViewText(R.id.text, textContent);
                    row.setTextColor(R.id.text, textColor);

                    views.addView(R.id.taskbody, row);

                    RemoteViews separator = new RemoteViews(Constants.PACKAGE, R.layout.widget_separator);
                    boolean isLastRow = (i == cursor.getCount() - 1) || (i == numberOfTasks - 1);
                    if (!isLastRow)
                        views.addView(R.id.taskbody, separator);
                }
                for (; i < numberOfTasks; i++) {
                    RemoteViews row = new RemoteViews(Constants.PACKAGE, R.layout.widget_row);
                    row.setViewVisibility(R.id.text, View.INVISIBLE);
                    views.addView(R.id.taskbody, row);
                }

            } catch (Exception e) {
                // can happen if database is not ready
                Log.e("WIDGET-UPDATE", "Error updating widget", e);
            } finally {
                if(cursor != null)
                    cursor.close();
            }

            Intent listIntent = new Intent(context, TaskListActivity.class);
            String customIntent = Preferences.getStringValue(WidgetConfigActivity.PREF_CUSTOM_INTENT
                    + widgetId);
            if(customIntent != null) {
                String serializedExtras = Preferences.getStringValue(WidgetConfigActivity.PREF_CUSTOM_EXTRAS
                        + widgetId);
                Bundle extras = AndroidUtilities.bundleFromSerializedString(serializedExtras);
                listIntent.putExtras(extras);
            }
            listIntent.putExtra(TaskListFragment.TOKEN_SOURCE, Constants.SOURCE_WIDGET);
            listIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            if(filter != null) {
                listIntent.putExtra(TaskListFragment.TOKEN_FILTER, filter);
                listIntent.setAction("L" + widgetId + filter.sqlQuery);
            } else {
                listIntent.setAction("L" + widgetId);
            }
            PendingIntent pListIntent = PendingIntent.getActivity(context, widgetId,
                    listIntent, PendingIntent.FLAG_CANCEL_CURRENT);
            if (pListIntent != null)
                views.setOnClickPendingIntent(R.id.taskbody, pListIntent);


            Intent editIntent = new Intent(context, TaskEditActivity.class);
            editIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            editIntent.putExtra(TaskEditFragment.OVERRIDE_FINISH_ANIM, false);
            if(filter != null) {
                editIntent.putExtra(TaskListFragment.TOKEN_FILTER, filter);
                if (filter.valuesForNewTasks != null) {
                    String values = AndroidUtilities.contentValuesToSerializedString(filter.valuesForNewTasks);
                    values = PermaSql.replacePlaceholders(values);
                    editIntent.putExtra(TaskEditFragment.TOKEN_VALUES, values);
                    editIntent.setAction("E" + widgetId + values);
                }
            } else {
                editIntent.setAction("E" + widgetId);
            }
            PendingIntent pEditIntent = PendingIntent.getActivity(context, -widgetId,
                    editIntent, 0);
            if (pEditIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_button, pEditIntent);
                views.setOnClickPendingIntent(R.id.widget_title, pEditIntent);
            }

            return views;
        }

        private boolean isDarkTheme() {
            int theme = ThemeService.getWidgetTheme();
            return (theme == R.style.Theme || theme == R.style.Theme_Transparent);
        }

        private boolean isLegacyTheme() {
            int theme = ThemeService.getWidgetTheme();
            return theme == THEME_LEGACY;
        }


        /**
         * The reason we use a bunch of different but almost identical layouts is that there is a bug with
         * Android 2.1 (level 7) that doesn't allow setting backgrounds on remote views. I know it's lame,
         * but I didn't see a better solution. Alternatively, we could disallow theming widgets on
         * Android 2.1.
         * @param context
         * @return
         */
        @SuppressWarnings("nls")
        private RemoteViews getThemedRemoteViews(Context context) {
            int theme = ThemeService.getWidgetTheme();
            String packageName = context.getPackageName();
            Resources r = context.getResources();
            int layout;
            RemoteViews views = null;

            int titleColor;
            int buttonDrawable;

            if (isLegacyTheme()) {
                views = new RemoteViews(packageName, R.layout.widget_initialized_legacy);
                views.setTextColor(R.id.widget_title, r.getColor(android.R.color.white));
                views.setInt(R.id.widget_button, "setImageResource", R.drawable.button_plus);
                views.setViewVisibility(R.id.widget_header_separator, View.GONE);
                return views;
            } else if(isDarkTheme()) {
                layout = (theme == R.style.Theme_Transparent ? R.layout.widget_initialized_dark_transparent : R.layout.widget_initialized_dark);
                titleColor = r.getColor(R.color.widget_text_color_dark);
                buttonDrawable = R.drawable.plus_button_blue;
            } else if (theme == R.style.Theme_White) {
                layout = R.layout.widget_initialized_red;
                titleColor = r.getColor(R.color.widget_text_color_light);
                buttonDrawable = R.drawable.plus_button_red;
            } else {
                layout = (theme == R.style.Theme_TransparentWhite ? R.layout.widget_initialized_transparent : R.layout.widget_initialized);
                titleColor = r.getColor(R.color.widget_text_color_light);
                buttonDrawable = R.drawable.plus_button_dark_blue;
            }

            views = new RemoteViews(packageName, layout);
            views.setTextColor(R.id.widget_title, titleColor);
            views.setInt(R.id.widget_button, "setImageResource", buttonDrawable);
            return views;
        }

        private int getNumberOfTasks() {
            Display display = ((WindowManager) this.getSystemService(
                    Context.WINDOW_SERVICE)).getDefaultDisplay();

            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);

            if(metrics.density <= 0.75)
                return 4;
            else
                return 5;
        }

        private Filter getFilter(int widgetId) {

            // base our filter off the inbox filter, replace stuff if we have it
            Filter filter = CoreFilterExposer.buildInboxFilter(getResources());
            String sql = Preferences.getStringValue(WidgetConfigActivity.PREF_SQL + widgetId);
            if(sql != null)
                filter.sqlQuery = sql;
            String title = Preferences.getStringValue(WidgetConfigActivity.PREF_TITLE + widgetId);
            if(title != null)
                filter.title = title;
            String contentValues = Preferences.getStringValue(WidgetConfigActivity.PREF_VALUES + widgetId);
            if(contentValues != null)
                filter.valuesForNewTasks = AndroidUtilities.contentValuesFromSerializedString(contentValues);

            String customComponent = Preferences.getStringValue(WidgetConfigActivity.PREF_CUSTOM_INTENT
                    + widgetId);
            if (customComponent != null) {
                ComponentName component = ComponentName.unflattenFromString(customComponent);
                filter = new FilterWithCustomIntent(filter.title, filter.title, filter.sqlQuery, filter.valuesForNewTasks);
                ((FilterWithCustomIntent) filter).customTaskList = component;
            }

            return filter;
        }

    }
}
