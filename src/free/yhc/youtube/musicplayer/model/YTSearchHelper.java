package free.yhc.youtube.musicplayer.model;

import static free.yhc.youtube.musicplayer.model.Utils.eAssert;
import static free.yhc.youtube.musicplayer.model.Utils.logW;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.LruCache;

public class YTSearchHelper {
    // TODO
    // Value below tightly coupled with memory consumption.
    // Later, this value should be configurable through user-preference interface.
    private static final int ENTRY_CACHE_SIZE           = 500;

    private static final int MSG_WHAT_SEARCH            = 0;
    private static final int MSG_WHAT_LOAD_THUMBNAIL    = 1;

    private final LruCache<Integer, YTSearchApi.Entry> mEntryCache = new LruCache<Integer, YTSearchApi.Entry>(ENTRY_CACHE_SIZE);

    private BGHandler                   mBgHandler      = null;
    private SearchDoneReceiver          mSearchRcvr     = null;
    private LoadThumbnailDoneReceiver   mThumbnailRcvr  = null;

    public interface SearchDoneReceiver {
        void searchDone(YTSearchHelper helper, SearchArg arg,
                          YTSearchApi.Result result, Err err);
    }

    public interface LoadThumbnailDoneReceiver {
        void loadThumbnailDone(YTSearchHelper helper, LoadThumbnailArg arg,
                               Bitmap bm, Err err);
    }



    public static class SearchArg {
        public Object   tag;   // user data tag
        public String   word;  // search word
        public int      starti;// start index
        public int      max;   // max size to search
        public SearchArg(Object aTag, String aWord, int aStarti, int aMax) {
            tag = aTag;
            word = aWord;
            starti = aStarti;
            max = aMax;
        }
    }

    public static class LoadThumbnailArg {
        public Object   tag;
        public String   url;
        public int      width;
        public int      height;
        public LoadThumbnailArg(Object aTag, String aUrl,
                                int aWidth, int aHeight) {
            tag = aTag;
            url = aUrl;
            width = aWidth;
            height = aHeight;
        }
    }

    private class BGThread extends HandlerThread {
        BGThread() {
            super("YTSearchHelper.BGThread",Process.THREAD_PRIORITY_BACKGROUND);
        }
        @Override
        protected void
        onLooperPrepared() {
            super.onLooperPrepared();
        }
    }

    private class BGHandler extends Handler {
        BGHandler(Looper looper) {
            super(looper);
        }

        private byte[]
        loadUrl(String url) throws YTMPException {
            byte[] data;
            try {
                data = new NetLoader().readData(new URL(url));
            } catch (MalformedURLException e) {
                throw new YTMPException(Err.INVALID_URL);
            }

            if (null == data)
                throw new YTMPException(Err.UNKNOWN);
            return data;
        }

        private YTSearchApi.Result
        parseFeed(byte[] xmlData) throws YTMPException {
            Document dom;
            try {
                dom = DocumentBuilderFactory.newInstance()
                                            .newDocumentBuilder()
                                            .parse(new ByteArrayInputStream(xmlData));
            } catch (IOException ie) {
                logW("YTSearchHelper.JobHandler : DOM IO error!");
                throw new YTMPException(Err.IO_UNKNOWN);
            } catch (SAXException se) {
                logW("YTSearchHelper.JobHandler : Parse unexpected format!");
                throw new YTMPException(Err.PARSER_UNEXPECTED_FORMAT);
            } catch (ParserConfigurationException pe) {
                logW("YTSearchHelper.JobHandler : Parse cofiguration exception!");
                throw new YTMPException(Err.PARSER_UNKNOWN);
            }

            return YTSearchApi.parseSearchFeed(dom);
        }

        private void
        handleSearch(SearchArg arg) {
            YTSearchApi.Result r;
            try {
                r = parseFeed(loadUrl(YTSearchApi.getFeedUrl(arg.word, arg.starti, arg.max)));
            } catch (YTMPException e) {
                eAssert(Err.NO_ERR != e.getError());
                sendSearchDone(arg, null, e.getError());
                return;
            }
            sendSearchDone(arg, r, Err.NO_ERR);
        }

        private void
        handlerLoadThumbnail(LoadThumbnailArg arg) {
            Bitmap bm;
            try {
                bm = Utils.decodeImage(loadUrl(arg.url), arg.width, arg.height);
            } catch (YTMPException e) {
                eAssert(Err.NO_ERR != e.getError());
                sendLoadThumbnailDone(arg, null, e.getError());
                return;
            }
            sendLoadThumbnailDone(arg, bm, Err.NO_ERR);
        }

        @Override
        public void
        handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_WHAT_SEARCH:
                handleSearch((SearchArg)msg.obj);
                break;
            case MSG_WHAT_LOAD_THUMBNAIL:
                handlerLoadThumbnail((LoadThumbnailArg)msg.obj);
                break;
            }
        }
    }

    private void
    sendSearchDone(final SearchArg arg, final YTSearchApi.Result res, final Err err) {
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void
            run() {
                mSearchRcvr.searchDone(YTSearchHelper.this, arg, res, err);
            }
        });
        return;
    }

    private void
    sendLoadThumbnailDone(final LoadThumbnailArg arg, final Bitmap bm, final Err err) {
        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void
            run() {
                mThumbnailRcvr.loadThumbnailDone(YTSearchHelper.this, arg, bm, err);
            }
        });
        return;
    }

    public YTSearchHelper() {
    }

    public void
    setSearchDoneRecevier(SearchDoneReceiver receiver) {
        mSearchRcvr = receiver;
    }

    public void
    setLoadThumbnailDoneRecevier(LoadThumbnailDoneReceiver receiver) {
        mThumbnailRcvr = receiver;
    }

    public void
    loadThumbnailAsync(LoadThumbnailArg arg) {
        Message msg = mBgHandler.obtainMessage(MSG_WHAT_LOAD_THUMBNAIL, arg);
        mBgHandler.sendMessage(msg);
    }

    public void
    searchAsync(SearchArg arg) {
        eAssert(0 < arg.starti && 0 < arg.max && arg.max <= YTSearchApi.NR_SEARCH_MAX);
        Message msg = mBgHandler.obtainMessage(MSG_WHAT_SEARCH, arg);
        mBgHandler.sendMessage(msg);
    }

    public void
    open() {
        HandlerThread hThread = new BGThread();
        hThread.start();
        mBgHandler = new BGHandler(hThread.getLooper());
    }

    public void
    close() {
        // TODO
        // Stop running thread!
        // Need to check that below code works as expected perfectly.
        // "interrupting thread" is quite annoying and unpredictable job!
        if (null != mBgHandler) {
            mBgHandler.getLooper().getThread().interrupt();
            mBgHandler.removeMessages(MSG_WHAT_SEARCH);
            mBgHandler.removeMessages(MSG_WHAT_LOAD_THUMBNAIL);
        }
    }
}