/*
 * Copyright 2010-2018 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transdroid.core.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import com.evernote.android.job.Job;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.androidannotations.annotations.SystemService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.transdroid.R;
import org.transdroid.core.app.settings.ApplicationSettings;
import org.transdroid.core.app.settings.NotificationSettings;
import org.transdroid.core.app.settings.ServerSetting;
import org.transdroid.core.gui.TorrentsActivity_;
import org.transdroid.core.gui.log.Log;
import org.transdroid.daemon.IDaemonAdapter;
import org.transdroid.daemon.Torrent;
import org.transdroid.daemon.task.DaemonTaskResult;
import org.transdroid.daemon.task.RetrieveTask;
import org.transdroid.daemon.task.RetrieveTaskSuccessResult;

import java.util.ArrayList;
import java.util.List;

@EBean
public class ServerCheckerJobRunner {

    @RootContext
    protected Context context;
    @Bean
    protected Log log;
    @Bean
    protected ConnectivityHelper connectivityHelper;
    @Bean
    protected NotificationSettings notificationSettings;
    @Bean
    protected ApplicationSettings applicationSettings;
    @SystemService
    protected NotificationManager notificationManager;

    Job.Result run() {

        if (!connectivityHelper.shouldPerformBackgroundActions() || !notificationSettings.isEnabledForTorrents()) {
            log.d(this,
                    "Skip the server checker service, as background data is disabled, the service is disabled or we are not connected.");
            return Job.Result.RESCHEDULE;
        }

        int notifyBase = 10000;
        for (ServerSetting server : applicationSettings.getAllServerSettings()) {

            // No need to check if the server is not properly configured or none of the two types of notifications are
            // enabled by the user for this specific server
            if (server.getType() == null || server.getAddress() == null || server.getAddress().equals("")
                    || !(server.shouldAlarmOnFinishedDownload() || server.shouldAlarmOnNewTorrent()))
                continue;

            // Get the statistics for the last time we checked this server
            JSONArray lastStats = applicationSettings.getServerLastStats(server);

            // Synchronously retrieve torrents listing
            IDaemonAdapter adapter = server.getServerAdapter(connectivityHelper.getConnectedNetworkName(), context);
            DaemonTaskResult result = RetrieveTask.create(adapter).execute(log);
            if (!(result instanceof RetrieveTaskSuccessResult)) {
                // Cannot retrieve torrents at this time
                continue;
            }
            List<Torrent> retrieved = ((RetrieveTaskSuccessResult) result).getTorrents();
            log.d(this, server.getName() + ": Retrieved torrent listing");

            // Preload filters to match torrent names
            String[] excludeFilters = null;
            String[] includeFilters = null;
            if (!TextUtils.isEmpty(server.getExcludeFilter())) {
                excludeFilters = server.getExcludeFilter().split("\\|");
                for (int i = 0; i < excludeFilters.length; i++) {
                    excludeFilters[i] = excludeFilters[i].toUpperCase();
                }
            }
            if (!TextUtils.isEmpty(server.getIncludeFilter())) {
                includeFilters = server.getIncludeFilter().split("\\|");
                for (int i = 0; i < includeFilters.length; i++) {
                    includeFilters[i] = includeFilters[i].toUpperCase();
                }
            }

            // Check for differences between the last and the current stats
            JSONArray currentStats = new JSONArray();
            List<Torrent> newTorrents = new ArrayList<>();
            List<Torrent> doneTorrents = new ArrayList<>();
            for (Torrent torrent : retrieved) {

                // Remember this torrent for the next time
                try {
                    currentStats.put(new JSONObject().put("id", torrent.getUniqueID()).put("done",
                            torrent.getPartDone() == 1F));
                } catch (JSONException e) {
                    // Can't build the JSON object; this should not happen and we can safely ignore it
                }

                // See if this torrent was done the last time we checked
                if (lastStats != null) {
                    Boolean wasDone = findLastDoneStat(lastStats, torrent);
                    boolean shouldNotify = matchFilters(torrent.getName(), excludeFilters, includeFilters);
                    if (server.shouldAlarmOnNewTorrent() && shouldNotify && wasDone == null) {
                        // This torrent wasn't present earlier
                        newTorrents.add(torrent);
                        continue;
                    }
                    if (server.shouldAlarmOnFinishedDownload() && shouldNotify && torrent.getPartDone() == 1F && wasDone != null && !wasDone)
                        // This torrent is now done, but wasn't before
                        doneTorrents.add(torrent);
                }

            }

            // Store the now-current statistics on torrents for the next time we check this server
            applicationSettings.setServerLastStats(server, currentStats);

            // Notify on new and now-done torrents for this server
            log.d(this, server.getName() + ": " + newTorrents.size() + " new torrents, " + doneTorrents.size()
                    + " newly finished torrents.");
            Intent i = new Intent(context, TorrentsActivity_.class);
            i.putExtra("org.transdroid.START_SERVER", server.getOrder());
            // Should start the main activity directly into this server
            PendingIntent pi = PendingIntent.getActivity(context, notifyBase + server.getOrder(), i,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            ArrayList<Torrent> affectedTorrents = new ArrayList<>(newTorrents.size() + doneTorrents.size());
            affectedTorrents.addAll(newTorrents);
            affectedTorrents.addAll(doneTorrents);

            String title;
            if (newTorrents.size() > 0 && doneTorrents.size() > 0) {
                // Note: use the 'one' plural iif 1 new torrent was added and 1 was newly finished
                title = context.getResources().getQuantityString(R.plurals.status_service_finished,
                        newTorrents.size() + doneTorrents.size() == 2 ? 1 : 2, Integer.toString(newTorrents.size()),
                        Integer.toString(doneTorrents.size()));
            } else if (newTorrents.size() > 0) {
                title = context.getResources().getQuantityString(R.plurals.status_service_added, newTorrents.size(),
                        Integer.toString(newTorrents.size()));
            } else if (doneTorrents.size() > 0) {
                title = context.getResources().getQuantityString(R.plurals.status_service_finished, doneTorrents.size(),
                        Integer.toString(doneTorrents.size()));
            } else {
                // No notification to show
                continue;
            }
            StringBuilder forStringBuilder = new StringBuilder();
            for (Torrent affected : affectedTorrents) {
                forStringBuilder.append(affected.getName()).append(", ");
            }
            String forString = forStringBuilder.toString();
            forString = forString.substring(0, forString.length() - 2);

            // Build the basic notification
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SERVER_CHECKER)
                    .setSmallIcon(R.drawable.ic_stat_notification)
                    .setTicker(title)
                    .setContentTitle(title).setContentText(forString)
                    .setNumber(affectedTorrents.size())
                    .setLights(notificationSettings.getDesiredLedColour(), 600, 1000)
                    .setSound(notificationSettings.getSound())
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (notificationSettings.shouldVibrate())
                builder.setVibrate(notificationSettings.getDefaultVibratePattern());

            // Add at most 5 lines with the affected torrents
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                final NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle(builder);
                if (affectedTorrents.size() < 6) {
                    for (Torrent affectedTorrent : affectedTorrents) {
                        inbox.addLine(affectedTorrent.getName());
                    }
                } else {
                    for (int j = 0; j < 4; j++) {
                        inbox.addLine(affectedTorrents.get(j).getName());
                    }
                    inbox.addLine(context.getString(R.string.status_service_andothers, affectedTorrents.get(5).getName()));
                }
                builder.setStyle(inbox);
            }
            notificationManager.notify(notifyBase + server.getOrder(), builder.build());

        }

        return Job.Result.SUCCESS;
    }

    private Boolean findLastDoneStat(JSONArray lastStats, Torrent torrent) {
        for (int i = 0; i < lastStats.length(); i++) {
            try {
                if (lastStats.getJSONObject(i).getString("id").equals(torrent.getUniqueID()))
                    return lastStats.getJSONObject(i).getBoolean("done");
            } catch (JSONException e) {
                return null;
            }
        }
        return null;
    }

    private boolean matchFilters(String name, String[] excludeFilters, String[] includeFilters) {
        String upperName = name.toUpperCase();
        if (includeFilters != null) {
            boolean include = false;
            for (String includeWord : includeFilters) {
                if (includeWord.equals("") || upperName.contains(includeWord)) {
                    include = true;
                    break;
                }
            }
            if (!include)
                return false;
        }
        if (excludeFilters != null) {
            for (String excludeWord : excludeFilters) {
                if (!excludeWord.equals("") && upperName.contains(excludeWord))
                    return false;
            }
        }
        return true;
    }

}
