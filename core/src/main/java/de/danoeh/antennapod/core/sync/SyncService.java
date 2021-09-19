package de.danoeh.antennapod.core.sync;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.core.ClientConfig;
import de.danoeh.antennapod.core.R;
import de.danoeh.antennapod.core.event.SyncServiceEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.core.preferences.GpodnetPreferences;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.service.download.AntennapodHttpClient;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.storage.DBTasks;
import de.danoeh.antennapod.core.storage.DBWriter;
import de.danoeh.antennapod.core.storage.DownloadRequestException;
import de.danoeh.antennapod.core.storage.DownloadRequester;
import de.danoeh.antennapod.core.util.FeedItemUtil;
import de.danoeh.antennapod.core.util.LongList;
import de.danoeh.antennapod.core.util.URLChecker;
import de.danoeh.antennapod.core.util.gui.NotificationUtils;
import de.danoeh.antennapod.net.sync.gpoddernet.GpodnetService;
import de.danoeh.antennapod.net.sync.model.EpisodeAction;
import de.danoeh.antennapod.net.sync.model.EpisodeActionChanges;
import de.danoeh.antennapod.net.sync.model.ISyncService;
import de.danoeh.antennapod.net.sync.model.SubscriptionChanges;
import de.danoeh.antennapod.net.sync.model.SyncServiceException;
import de.danoeh.antennapod.net.sync.model.UploadChangesResponse;
import de.danoeh.antennapod.net.sync.nextcloud.NextcloudSyncService;
import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;

import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class SyncService extends Worker {
    public static final String TAG = "SyncService";

    private static final String WORK_ID_SYNC = "SyncServiceWorkId";
    private static final ReentrantLock lock = new ReentrantLock();
    private final SynchronizationQueue synchronizationQueue;

    public SyncService(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        synchronizationQueue = new SynchronizationQueue(context);
    }

    @Override
    @NonNull
    public Result doWork() {
        ISyncService activeSyncProvider = getActiveSyncProvider();
        if (activeSyncProvider == null) {
            return Result.success();
        }

        SynchronizationSettings.updateLastSynchronizationAttempt();
        try {
            activeSyncProvider.login();
            EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_subscriptions));
            syncSubscriptions(activeSyncProvider);
            syncEpisodeActions(activeSyncProvider);
            activeSyncProvider.logout();
            clearErrorNotifications();
            EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_success));
            SynchronizationSettings.setLastSynchronizationAttemptSuccess(true);
            return Result.success();
        } catch (SyncServiceException e) {
            EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_error));
            SynchronizationSettings.setLastSynchronizationAttemptSuccess(false);
            Log.e(TAG, Log.getStackTraceString(e));
            if (getRunAttemptCount() % 3 == 2) {
                // Do not spam users with notification and retry before notifying
                updateErrorNotification(e);
            }
            return Result.retry();
        }
    }

    public static void clearQueue(Context context) {
        SynchronizationQueue synchronizationQueue = new SynchronizationQueue(context);
        executeLockedAsync(synchronizationQueue::clearQueue);
    }

    public static void enqueueFeedAdded(Context context, String downloadUrl) {
        if (!hasActiveSyncProvider()) {
            return;
        }
        executeLockedAsync(() -> {
            try {
                new SynchronizationQueue(context).enqueueFeedAdded(downloadUrl);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            sync(context);
        });
    }

    public static void enqueueFeedRemoved(Context context, String downloadUrl) {
        if (!hasActiveSyncProvider()) {
            return;
        }
        executeLockedAsync(() -> {
            try {
                new SynchronizationQueue(context).enqueueFeedRemoved(downloadUrl);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            sync(context);
        });
    }

    public static void enqueueEpisodeAction(Context context, EpisodeAction action) {
        if (!hasActiveSyncProvider()) {
            return;
        }
        executeLockedAsync(() -> {
            try {
                new SynchronizationQueue(context).enqueueEpisodeAction(action);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            sync(context);
        });
    }

    public static void enqueueEpisodePlayed(Context context, FeedMedia media, boolean completed) {
        if (!hasActiveSyncProvider()) {
            return;
        }
        if (media.getItem() == null) {
            return;
        }
        if (media.getStartPosition() < 0 || (!completed && media.getStartPosition() >= media.getPosition())) {
            return;
        }
        EpisodeAction action = new EpisodeAction.Builder(media.getItem(), EpisodeAction.PLAY)
                .currentTimestamp()
                .started(media.getStartPosition() / 1000)
                .position((completed ? media.getDuration() : media.getPosition()) / 1000)
                .total(media.getDuration() / 1000)
                .build();
        SyncService.enqueueEpisodeAction(context, action);
    }

    public static void sync(Context context) {
        OneTimeWorkRequest workRequest = getWorkRequest().build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ID_SYNC, ExistingWorkPolicy.REPLACE, workRequest);
        EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_started));
    }

    public static void syncImmediately(Context context) {
        OneTimeWorkRequest workRequest = getWorkRequest()
                .setInitialDelay(0L, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ID_SYNC, ExistingWorkPolicy.REPLACE, workRequest);
        EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_started));
    }

    public static void fullSync(Context context) {
        executeLockedAsync(() -> {
            SynchronizationSettings.resetTimestamps();
            OneTimeWorkRequest workRequest = getWorkRequest()
                    .setInitialDelay(0L, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_ID_SYNC, ExistingWorkPolicy.REPLACE, workRequest);
            EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_started));
        });
    }

    private void syncSubscriptions(ISyncService syncServiceImpl) throws SyncServiceException {
        final long lastSync = SynchronizationSettings.getLastSubscriptionSynchronizationTimestamp();
        final List<String> localSubscriptions = DBReader.getFeedListDownloadUrls();
        SubscriptionChanges subscriptionChanges = syncServiceImpl.getSubscriptionChanges(lastSync);
        long newTimeStamp = subscriptionChanges.getTimestamp();

        List<String> queuedRemovedFeeds = synchronizationQueue.getQueuedRemovedFeeds();
        List<String> queuedAddedFeeds = synchronizationQueue.getQueuedAddedFeeds();

        Log.d(TAG, "Downloaded subscription changes: " + subscriptionChanges);
        for (String downloadUrl : subscriptionChanges.getAdded()) {
            if (!URLChecker.containsUrl(localSubscriptions, downloadUrl) && !queuedRemovedFeeds.contains(downloadUrl)) {
                Feed feed = new Feed(downloadUrl, null);
                try {
                    DownloadRequester.getInstance().downloadFeed(getApplicationContext(), feed);
                } catch (DownloadRequestException e) {
                    e.printStackTrace();
                }
            }
        }

        // remove subscription if not just subscribed (again)
        for (String downloadUrl : subscriptionChanges.getRemoved()) {
            if (!queuedAddedFeeds.contains(downloadUrl)) {
                DBTasks.removeFeedWithDownloadUrl(getApplicationContext(), downloadUrl);
            }
        }

        if (lastSync == 0) {
            Log.d(TAG, "First sync. Adding all local subscriptions.");
            queuedAddedFeeds = localSubscriptions;
            queuedAddedFeeds.removeAll(subscriptionChanges.getAdded());
            queuedRemovedFeeds.removeAll(subscriptionChanges.getRemoved());
        }

        if (queuedAddedFeeds.size() > 0 || queuedRemovedFeeds.size() > 0) {
            Log.d(TAG, "Added: " + StringUtils.join(queuedAddedFeeds, ", "));
            Log.d(TAG, "Removed: " + StringUtils.join(queuedRemovedFeeds, ", "));

            lock.lock();
            try {
                UploadChangesResponse uploadResponse = syncServiceImpl
                        .uploadSubscriptionChanges(queuedAddedFeeds, queuedRemovedFeeds);
                synchronizationQueue.clearFeedQueues();
                newTimeStamp = uploadResponse.timestamp;
            } finally {
                lock.unlock();
            }
        }
        SynchronizationSettings.setLastSubscriptionSynchronizationAttemptTimestamp(newTimeStamp);
    }

    private void syncEpisodeActions(ISyncService syncServiceImpl) throws SyncServiceException {
        final long lastSync = SynchronizationSettings.getLastEpisodeActionSynchronizationTimestamp();
        EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_episodes_download));
        EpisodeActionChanges getResponse = syncServiceImpl.getEpisodeActionChanges(lastSync);
        long newTimeStamp = getResponse.getTimestamp();
        List<EpisodeAction> remoteActions = getResponse.getEpisodeActions();
        processEpisodeActions(remoteActions);

        // upload local actions
        EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_episodes_upload));
        List<EpisodeAction> queuedEpisodeActions = synchronizationQueue.getQueuedEpisodeActions();
        if (lastSync == 0) {
            EventBus.getDefault().postSticky(new SyncServiceEvent(R.string.sync_status_upload_played));
            List<FeedItem> readItems = DBReader.getPlayedItems();
            Log.d(TAG, "First sync. Upload state for all " + readItems.size() + " played episodes");
            for (FeedItem item : readItems) {
                FeedMedia media = item.getMedia();
                if (media == null) {
                    continue;
                }
                EpisodeAction played = new EpisodeAction.Builder(item, EpisodeAction.PLAY)
                        .currentTimestamp()
                        .started(media.getDuration() / 1000)
                        .position(media.getDuration() / 1000)
                        .total(media.getDuration() / 1000)
                        .build();
                queuedEpisodeActions.add(played);
            }
        }
        if (queuedEpisodeActions.size() > 0) {
            lock.lock();
            try {
                Log.d(TAG, "Uploading " + queuedEpisodeActions.size() + " actions: "
                        + StringUtils.join(queuedEpisodeActions, ", "));
                UploadChangesResponse postResponse = syncServiceImpl.uploadEpisodeActions(queuedEpisodeActions);
                newTimeStamp = postResponse.timestamp;
                Log.d(TAG, "Upload episode response: " + postResponse);
                synchronizationQueue.clearEpisodeActionQueue();
            } finally {
                lock.unlock();
            }
        }
        SynchronizationSettings.setLastEpisodeActionSynchronizationAttemptTimestamp(newTimeStamp);
    }


    private synchronized void processEpisodeActions(List<EpisodeAction> remoteActions) {
        Log.d(TAG, "Processing " + remoteActions.size() + " actions");
        if (remoteActions.size() == 0) {
            return;
        }

        Map<Pair<String, String>, EpisodeAction> playActionsToUpdate = EpisodeActionFilter
                .getRemoteActionsOverridingLocalActions(remoteActions, synchronizationQueue.getQueuedEpisodeActions());
        LongList queueToBeRemoved = new LongList();
        List<FeedItem> updatedItems = new ArrayList<>();
        for (EpisodeAction action : playActionsToUpdate.values()) {
            String guid = GuidValidator.isValidGuid(action.getGuid()) ? action.getGuid() : null;
            FeedItem feedItem = DBReader.getFeedItemByGuidOrEpisodeUrl(guid, action.getEpisode());
            if (feedItem == null) {
                Log.i(TAG, "Unknown feed item: " + action);
                continue;
            }
            if (action.getAction() == EpisodeAction.NEW) {
                DBWriter.markItemPlayed(feedItem, FeedItem.UNPLAYED, true);
                continue;
            }
            Log.d(TAG, "Most recent play action: " + action.toString());
            FeedMedia media = feedItem.getMedia();
            media.setPosition(action.getPosition() * 1000);
            if (FeedItemUtil.hasAlmostEnded(feedItem.getMedia())) {
                Log.d(TAG, "Marking as played");
                feedItem.setPlayed(true);
                queueToBeRemoved.add(feedItem.getId());
            }
            updatedItems.add(feedItem);

        }
        DBWriter.removeQueueItem(getApplicationContext(), false, queueToBeRemoved.toArray());
        DBReader.loadAdditionalFeedItemListData(updatedItems);
        DBWriter.setItemList(updatedItems);
    }

    private void clearErrorNotifications() {
        NotificationManager nm = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(R.id.notification_gpodnet_sync_error);
        nm.cancel(R.id.notification_gpodnet_sync_autherror);
    }

    private void updateErrorNotification(SyncServiceException exception) {
        if (!UserPreferences.gpodnetNotificationsEnabled()) {
            Log.d(TAG, "Skipping sync error notification because of user setting");
            return;
        }
        Log.d(TAG, "Posting sync error notification");
        final String description = getApplicationContext().getString(R.string.gpodnetsync_error_descr)
                + exception.getMessage();

        Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage(
                getApplicationContext().getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(),
                R.id.pending_intent_sync_error, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(getApplicationContext(),
                NotificationUtils.CHANNEL_ID_SYNC_ERROR)
                .setContentTitle(getApplicationContext().getString(R.string.gpodnetsync_error_title))
                .setContentText(description)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(description))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_notification_sync_error)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
        NotificationManager nm = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(R.id.notification_gpodnet_sync_error, notification);
    }

    private static OneTimeWorkRequest.Builder getWorkRequest() {
        Constraints.Builder constraints = new Constraints.Builder();
        if (UserPreferences.isAllowMobileFeedRefresh()) {
            constraints.setRequiredNetworkType(NetworkType.CONNECTED);
        } else {
            constraints.setRequiredNetworkType(NetworkType.UNMETERED);
        }

        return new OneTimeWorkRequest.Builder(SyncService.class)
                .setConstraints(constraints.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .setInitialDelay(5L, TimeUnit.SECONDS); // Give it some time, so other actions can be queued
    }

    /**
     * Take the lock and execute runnable (to prevent changes to preferences being lost when enqueueing while sync is
     * in progress). If the lock is free, the runnable is directly executed in the calling thread to prevent overhead.
     */
    private static void executeLockedAsync(Runnable runnable) {
        if (lock.tryLock()) {
            try {
                runnable.run();
            } finally {
                lock.unlock();
            }
        } else {
            Completable.fromRunnable(() -> {
                lock.lock();
                try {
                    runnable.run();
                } finally {
                    lock.unlock();
                }
            }).subscribeOn(Schedulers.io())
                    .subscribe();
        }
    }

    private ISyncService getActiveSyncProvider() {
        String selectedSyncProviderKey = SynchronizationSettings.getSelectedSyncProviderKey();
        SynchronizationProviderViewData selectedService = SynchronizationProviderViewData
                .valueOf(selectedSyncProviderKey);
        switch (selectedService) {
            case GPODDER_NET:
                return new GpodnetService(AntennapodHttpClient.getHttpClient(),
                        GpodnetPreferences.getHosturl(), GpodnetPreferences.getDeviceID(),
                        GpodnetPreferences.getUsername(), GpodnetPreferences.getPassword());
            case NEXTCLOUD_GPODDER:
                return new NextcloudSyncService(getApplicationContext(), ClientConfig.USER_AGENT);
            default:
                return null;
        }
    }

    private static boolean hasActiveSyncProvider() {
        return SynchronizationSettings.getSelectedSyncProviderKey() != null
                && SynchronizationSettings.isProviderConnected();
    }
}
