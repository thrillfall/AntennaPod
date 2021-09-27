package de.danoeh.antennapod.core.service.download.handler;

import android.util.Log;

import org.json.JSONException;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

import javax.xml.parsers.ParserConfigurationException;

import de.danoeh.antennapod.core.service.download.DownloadRequest;
import de.danoeh.antennapod.core.service.download.DownloadStatus;
import de.danoeh.antennapod.core.storage.DownloadRequester;
import de.danoeh.antennapod.core.util.DownloadError;
import de.danoeh.antennapod.core.util.InvalidFeedException;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.parser.feed.FeedHandler;
import de.danoeh.antennapod.parser.feed.FeedHandlerResult;
import de.danoeh.antennapod.parser.feed.UnsupportedFeedtypeException;

public class FeedParserTask implements Callable<FeedHandlerResult> {
    private static final String TAG = "FeedParserTask";
    private final DownloadRequest request;
    private DownloadStatus downloadStatus;
    private boolean successful = true;

    public FeedParserTask(DownloadRequest request) {
        this.request = request;
    }

    @Override
    public FeedHandlerResult call() {
        Feed feed = new Feed(request.getSource(), request.getLastModified());
        feed.setFile_url(request.getDestination());
        feed.setId(request.getFeedfileId());
        feed.setDownloaded(true);
        feed.setPreferences(new FeedPreferences(0, true, FeedPreferences.AutoDeleteAction.GLOBAL,
                VolumeAdaptionSetting.OFF, request.getUsername(), request.getPassword()));
        feed.setPageNr(request.getArguments().getInt(DownloadRequester.REQUEST_ARG_PAGE_NR, 0));

        DownloadError reason = null;
        String reasonDetailed = null;
        FeedHandler feedHandler = new FeedHandler();

        FeedHandlerResult feedHandlerResult = null;
        try {
            feedHandlerResult = feedHandler.parseFeed(feed);
            Log.d(TAG, feedHandlerResult.feed.getTitle() + " parsed");
            checkFeedData(feedHandlerResult.feed);
        } catch (SAXException | IOException | ParserConfigurationException | JSONException e) {
            successful = false;
            e.printStackTrace();
            reason = DownloadError.ERROR_PARSER_EXCEPTION;
            reasonDetailed = e.getMessage();
        } catch (UnsupportedFeedtypeException e) {
            e.printStackTrace();
            successful = false;
            reason = DownloadError.ERROR_UNSUPPORTED_TYPE;
            if ("html".equalsIgnoreCase(e.getRootElement())) {
                reason = DownloadError.ERROR_UNSUPPORTED_TYPE_HTML;
            }
            reasonDetailed = e.getMessage();
        } catch (InvalidFeedException e) {
            e.printStackTrace();
            successful = false;
            reason = DownloadError.ERROR_PARSER_EXCEPTION;
            reasonDetailed = e.getMessage();
        } finally {
            File feedFile = new File(request.getDestination());
            if (feedFile.exists()) {
                boolean deleted = feedFile.delete();
                Log.d(TAG, "Deletion of file '" + feedFile.getAbsolutePath() + "' "
                        + (deleted ? "successful" : "FAILED"));
            }
        }

        if (successful) {
            downloadStatus = new DownloadStatus(feed, feed.getHumanReadableIdentifier(), DownloadError.SUCCESS,
                                                successful, reasonDetailed, request.isInitiatedByUser());
            return feedHandlerResult;
        } else {
            downloadStatus = new DownloadStatus(feed, feed.getTitle(), reason, successful,
                                                reasonDetailed, request.isInitiatedByUser());
            return null;
        }
    }

    public boolean isSuccessful() {
        return successful;
    }

    /**
     * Checks if the feed was parsed correctly.
     */
    private void checkFeedData(Feed feed) throws InvalidFeedException {
        if (feed.getTitle() == null) {
            throw new InvalidFeedException("Feed has no title");
        }
        checkFeedItems(feed);
    }

    private void checkFeedItems(Feed feed) throws InvalidFeedException  {
        for (FeedItem item : feed.getItems()) {
            if (item.getTitle() == null) {
                throw new InvalidFeedException("Item has no title: " + item);
            }
        }
    }

    public DownloadStatus getDownloadStatus() {
        return downloadStatus;
    }
}
