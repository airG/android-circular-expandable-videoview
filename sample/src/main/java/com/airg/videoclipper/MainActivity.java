/*
 * ****************************************************************************
 *   Copyright  2017 airG Inc.                                                 *
 *                                                                             *
 *   Licensed under the Apache License, Version 2.0 (the "License");           *
 *   you may not use this file except in compliance with the License.          *
 *   You may obtain a copy of the License at                                   *
 *                                                                             *
 *       http://www.apache.org/licenses/LICENSE-2.0                            *
 *                                                                             *
 *   Unless required by applicable law or agreed to in writing, software       *
 *   distributed under the License is distributed on an "AS IS" BASIS,         *
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *   See the License for the specific language governing permissions and       *
 *   limitations under the License.                                            *
 * ***************************************************************************
 */

package com.airg.videoclipper;

import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.airg.android.logging.Logger;
import com.airg.android.logging.TaggedLogger;
import com.airg.android.circlevideo.CircularExpandableVideoView;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity
        extends AppCompatActivity
        implements MediaPlayer.OnCompletionListener,
        CircularExpandableVideoView.VideoSurfaceViewListener, DialogInterface.OnClickListener {
    private final TaggedLogger LOG = Logger.tag("VideoActivity");

    private CircularExpandableVideoView videoView;
    private TextView text;

    private AlertDialog dialog;

    private FilesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoView = (CircularExpandableVideoView) findViewById(R.id.video);
        text = (TextView) findViewById(R.id.text);

        adapter = new FilesAdapter();

        Uri videoUri = getIntent().getData();
        if (null == videoUri) {
            showPickerDialog();
            return;
        }

        LOG.d("Video uri: %s", videoUri);

        videoView.setVideoUri(videoUri);
        videoView.setListener(this);
    }

    @Override
    protected void onPause() {
        if (null != dialog) {
            dialog.dismiss();
            dialog = null;
        }

        videoView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        videoView.play();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_change:
                showPickerDialog();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    private void showPickerDialog() {
        dialog = new AlertDialog.Builder(this)
                .setTitle("Pick Video")
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setAdapter(adapter, this)
                .show();
    }

    private void relaunchWithVideo(final File file) {
        final Intent intent = new Intent(this, MainActivity.class);
        intent.setData(Uri.fromFile(file));
        startActivity(intent);
        finish();
    }

    @Override
    public void onVideoEnd(MediaPlayer mp) {
        videoView.play();
    }

    @Override
    public void onMaximized() {
        LOG.d("Maximized");
        text.bringToFront();
        showMinimizeInstructions();
    }

    private void showMinimizeInstructions() {
        Toast.makeText(this, "Swipe down to minimize", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMinimized() {
        LOG.d("Minimized");
    }

    @Override
    public void onClick() {
        showMinimizeInstructions();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        relaunchWithVideo(adapter.getItem(which));
    }

    @Override
    public void onPrepared(final MediaPlayer mp) {
        videoView.play();
    }

    private static class DialogItem {

        final View itemView;
        final TextView text;

        private DialogItem(final View view) {
            itemView = view;
            text = (TextView) itemView.findViewById(android.R.id.text1);
            view.setTag(this);
        }
    }

    private class FileEnumerator extends AsyncTask<File, Integer, List<File>> implements FilenameFilter {

        @Override
        protected List<File> doInBackground(File... files) {
            final List<File> results = new ArrayList<>();

            for (final File entry : files) {
                if (!entry.isDirectory()) continue;

                final File[] dirFiles = entry.listFiles(this);

                if (null == dirFiles) {
                    LOG.d("No files in %s", entry.getPath());
                    continue;
                }

                Collections.addAll(results, dirFiles);

                LOG.d("%d files found in %s", dirFiles.length, entry.getPath());
            }

            return results;
        }

        @Override
        protected void onPostExecute(List<File> files) {
            super.onPostExecute(files);
            adapter.files.clear();
            adapter.files.addAll(files);
            adapter.notifyDataSetChanged();
        }

        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase(Locale.ENGLISH).endsWith(".mp4");
        }
    }

    private class FilesAdapter extends BaseAdapter {

        private final List<File> files = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        private FilesAdapter() {
            new FileEnumerator().execute(new File(Environment.getExternalStorageDirectory(), "Movies"));
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public File getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final DialogItem item = null == convertView ? newView(parent) : (DialogItem) convertView.getTag();

            item.text.setText(getItem(position).getName());

            return item.itemView;
        }

        private DialogItem newView(final ViewGroup parent) {
            return new DialogItem(inflater.inflate(android.R.layout.select_dialog_singlechoice, parent, false));
        }
    }
}
