package com.zegoggles.smssync.service;

import android.database.Cursor;
import android.os.AsyncTask;
import android.util.Log;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.XOAuth2AuthenticationFailedException;
import com.squareup.otto.Subscribe;
import com.zegoggles.smssync.App;
import com.zegoggles.smssync.R;
import com.zegoggles.smssync.mail.CallFormatter;
import com.zegoggles.smssync.mail.ConversionResult;
import com.zegoggles.smssync.mail.DataType;
import com.zegoggles.smssync.mail.MessageConverter;
import com.zegoggles.smssync.preferences.AuthPreferences;
import com.zegoggles.smssync.preferences.Preferences;
import com.zegoggles.smssync.service.exception.ConnectivityException;
import com.zegoggles.smssync.service.exception.RequiresLoginException;
import com.zegoggles.smssync.service.state.BackupState;
import com.zegoggles.smssync.service.state.SmsSyncState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity.refreshOAuth2Token;
import static com.zegoggles.smssync.mail.DataType.*;
import static com.zegoggles.smssync.service.state.SmsSyncState.*;

/**
 * BackupTask does all the work
 */
class BackupTask extends AsyncTask<BackupConfig, BackupState, BackupState> {
    private final SmsBackupService service;
    private final BackupItemsFetcher fetcher;
    private final MessageConverter converter;
    private final CalendarSyncer calendarSyncer;

    BackupTask(@NotNull SmsBackupService service) {
        this.service = service;
        this.fetcher = new BackupItemsFetcher(service,
                new BackupQueryBuilder(service, service.getContacts()));
        this.converter = new MessageConverter(service, AuthPreferences.getUserEmail(service));

        if (Preferences.isCallLogCalendarSyncEnabled(service)) {
            calendarSyncer = new CalendarSyncer(
                service,
                service.getCalendars(),
                Preferences.getCallLogCalendarId(service),
                converter.getPersonLookup(),
                new CallFormatter(service.getResources())
            );
        } else {
            calendarSyncer = null;
        }
    }

    @Override
    protected void onPreExecute() {
        App.bus.register(this);
    }

    @Subscribe public void userCanceled(UserCanceled canceled) {
        cancel(false);
    }

    @Override
    protected BackupState doInBackground(BackupConfig... params) {
        final BackupConfig config = params[0];
        if (config.skip) {
            appLog(R.string.app_log_skip_backup_skip_messages);
            for (DataType type : new DataType[] { SMS, MMS, CALLLOG }) {
                type.setMaxSyncedDate(service, fetcher.getMaxData(type));
            }
            Log.i(TAG, "All messages skipped.");
            return new BackupState(FINISHED_BACKUP, 0, 0, BackupType.MANUAL, null, null);
        }

        Cursor smsItems = null;
        Cursor mmsItems = null;
        Cursor callLogItems = null;
        Cursor whatsAppItems = null;
        final int smsCount, mmsCount, callLogCount, whatsAppItemsCount;
        try {
            service.acquireLocks();
            int max = config.maxItemsPerSync;

            smsItems = fetcher.getItemsForDataType(SMS, config.groupToBackup, max);
            smsCount = smsItems != null ? smsItems.getCount() : 0;
            max -= smsCount;

            mmsItems = fetcher.getItemsForDataType(MMS, config.groupToBackup, max);
            mmsCount = mmsItems != null ? mmsItems.getCount() : 0;
            max -= mmsCount;

            callLogItems = fetcher.getItemsForDataType(CALLLOG, config.groupToBackup, max);
            callLogCount = callLogItems != null ? callLogItems.getCount() : 0;
            max -= callLogCount;

            whatsAppItems = fetcher.getItemsForDataType(DataType.WHATSAPP, config.groupToBackup, max);
            whatsAppItemsCount = whatsAppItems != null ? whatsAppItems.getCount() : 0;

            final int itemsToSync = smsCount + mmsCount + callLogCount + whatsAppItemsCount;

            if (itemsToSync > 0) {
                if (!AuthPreferences.isLoginInformationSet(service)) {
                    appLog(R.string.app_log_missing_credentials);
                    return transition(ERROR, new RequiresLoginException());
                } else {
                    appLog(R.string.app_log_backup_messages, smsCount, mmsCount, callLogCount);
                    return backup(config, smsItems, mmsItems, callLogItems, whatsAppItems, itemsToSync);
                }
            } else {
                appLog(R.string.app_log_skip_backup_no_items);

                if (Preferences.isFirstBackup(service)) {
                    // If this is the first backup we need to write something to MAX_SYNCED_DATE
                    // such that we know that we've performed a backup before.
                    SMS.setMaxSyncedDate(service, Defaults.MAX_SYNCED_DATE);
                    MMS.setMaxSyncedDate(service, Defaults.MAX_SYNCED_DATE);
                }
                Log.i(TAG, "Nothing to do.");
                return transition(FINISHED_BACKUP, null);
            }
        } catch (XOAuth2AuthenticationFailedException e) {
            if (e.getStatus() == 400) {
                Log.d(TAG, "need to perform xoauth2 token refresh");
                if (config.tries < 1 && refreshOAuth2Token(service)) {
                    try {
                        // we got a new token, let's retry one more time - we need to pass in a new store object
                        // since the auth params on it are immutable
                        return doInBackground(config.retryWithStore(service.getBackupImapStore()));
                    } catch (MessagingException ignored) {
                        Log.w(TAG, ignored);
                    }
                } else {
                    Log.w(TAG, "no new token obtained, giving up");
                }
            } else {
                Log.w(TAG, "unexpected xoauth status code " + e.getStatus());
            }
            return transition(ERROR, e);
        } catch (AuthenticationFailedException e) {
            return transition(ERROR, e);
        } catch (MessagingException e) {
            return transition(ERROR, e);
        } catch (ConnectivityException e) {
            return transition(ERROR, e);
        } finally {
            service.releaseLocks();
            try {
                if (smsItems != null) smsItems.close();
                if (mmsItems != null) mmsItems.close();
                if (callLogItems != null) callLogItems.close();
                if (whatsAppItems != null) whatsAppItems.close();
            } catch (Exception ignore) {
                Log.e(TAG, "error", ignore);
            }
        }
    }

    private void appLog(int id, Object... args) {
        service.appLog(id, args);
    }

    private BackupState transition(SmsSyncState smsSyncState, Exception exception) {
        return service.getState().transition(smsSyncState, exception);
    }

    @Override
    protected void onProgressUpdate(BackupState... progress) {
        if (progress != null  && progress.length > 0 && !isCancelled()) {
            post(progress[0]);
        }
    }

    @Override
    protected void onPostExecute(BackupState result) {
        if (result != null) {
            post(result);
        }
        App.bus.unregister(this);
    }

    @Override
    protected void onCancelled() {
        post(transition(CANCELED_BACKUP, null));
        App.bus.unregister(this);
    }

    private void post(BackupState state) {
        App.bus.post(state);
    }

    private BackupState backup(BackupConfig config,
                               @Nullable Cursor smsItems,
                               @Nullable Cursor mmsItems,
                               @Nullable Cursor callLogItems,
                               @Nullable Cursor whatsAppItems,
                               final int itemsToSync) throws MessagingException {
        Log.i(TAG, String.format(Locale.ENGLISH, "Starting backup (%d messages)", itemsToSync));

        publish(LOGIN);

        Folder smsmmsfolder   = (smsItems != null || mmsItems != null) ? config.imap.getFolder(SMS) : null;
        Folder callLogfolder  = callLogItems  != null ?  config.imap.getFolder(CALLLOG) : null;
        Folder whatsAppFolder = whatsAppItems != null ? config.imap.getFolder(WHATSAPP) : null;

        try {
            Cursor curCursor;
            DataType dataType = null;
            publish(CALC);
            int backedUpItems = 0;
            while (!isCancelled() && backedUpItems < itemsToSync) {
                if (smsItems != null && smsItems.moveToNext()) {
                    dataType = SMS;
                    curCursor = smsItems;
                } else if (mmsItems != null && mmsItems.moveToNext()) {
                    dataType = MMS;
                    curCursor = mmsItems;
                } else if (callLogItems != null && callLogItems.moveToNext()) {
                    dataType = CALLLOG;
                    curCursor = callLogItems;
                } else if (whatsAppItems != null && whatsAppItems.moveToNext()) {
                    dataType = DataType.WHATSAPP;
                    curCursor = whatsAppItems;
                } else break; // no more items available

                if (LOCAL_LOGV) Log.v(TAG, "backing up: " + dataType);
                ConversionResult result = converter.cursorToMessages(curCursor, config.maxMessagePerRequest, dataType);
                List<Message> messages = result.messageList;
                if (!messages.isEmpty()) {
                    if (LOCAL_LOGV)
                        Log.v(TAG, String.format(Locale.ENGLISH, "sending %d %s message(s) to server.",
                                messages.size(), dataType));

                    dataType.setMaxSyncedDate(service, result.maxDate);
                    switch (dataType) {
                        case MMS:
                        case SMS:
                            appendMessages(smsmmsfolder, messages);
                            break;
                        case CALLLOG:
                            appendMessages(callLogfolder, messages);
                            if (calendarSyncer != null) {
                                calendarSyncer.syncCalendar(result);
                            }
                            break;
                        case WHATSAPP:
                            appendMessages(whatsAppFolder, messages);
                            break;
                    }
                }
                backedUpItems += messages.size();
                publishProgress(new BackupState(BACKUP, backedUpItems, itemsToSync, config.backupType, dataType, null));
            }
            return new BackupState(FINISHED_BACKUP,
                    backedUpItems,
                    itemsToSync,
                    config.backupType, dataType, null);
        } finally {
            if (smsmmsfolder != null) smsmmsfolder.close();
            if (callLogfolder != null) callLogfolder.close();
            if (whatsAppFolder != null) whatsAppFolder.close();
        }
    }

    private void appendMessages(Folder folder, List<Message> messages) throws MessagingException {
        if (folder != null) {
            folder.appendMessages(messages.toArray(new Message[messages.size()]));
        }
    }

    private void publish(SmsSyncState state) {
        publish(state, null);
    }

    private void publish(SmsSyncState state, Exception exception) {
        publishProgress(service.getState().transition(state, exception));
    }
}