package ru.tubetv.app;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Retry only the selected API file, not an unrelated HLS playlist or segment. */
@OptIn(markerClass = UnstableApi.class)
final class FileFallbackDataSource implements DataSource {
    static final class Target {
        final Uri primary;
        final Uri download;
        // Shared by this player's data sources so later Range/seek requests skip a dead primary.
        volatile boolean selected;

        Target(String primary, String download) {
            this.primary = Uri.parse(primary);
            this.download = Uri.parse(download);
        }
    }

    private final DataSource upstream;
    private final Target target;

    FileFallbackDataSource(DataSource upstream, Target target) {
        this.upstream = upstream;
        this.target = target;
    }

    @Override public long open(DataSpec spec) throws IOException {
        DataSpec request = target.selected && spec.uri.equals(target.primary)
                ? spec.withUri(target.download) : spec;
        try {
            return upstream.open(request);
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            if (!request.uri.equals(target.primary)
                    || error.responseCode != 404 && error.responseCode != 410) throw error;
            upstream.close();
            // withUri retains position, length, headers, HTTP method, and all other request fields.
            long length = upstream.open(spec.withUri(target.download));
            target.selected = true;
            return length;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return upstream.read(buffer, offset, length);
    }
    @Override public void close() throws IOException { upstream.close(); }
    @Override public Uri getUri() { return upstream.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
}
