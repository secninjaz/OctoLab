package com.philosophicalhacker.lib;

import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import io.reactivex.disposables.Disposable;

class LoadRequest<T> implements Disposable {
    private final LoaderManager loaderManager;
    private final boolean forceReload;
    private final int id;

    LoadRequest(LoaderManager loaderManager, boolean forceReload, int id) {
        this.loaderManager = loaderManager;
        this.forceReload = forceReload;
        this.id = id;
    }

    void execute(RxLoaderCallbacks<T> callbacks) {
        if (forceReload) {
            loaderManager.restartLoader(id, null, callbacks);
        } else {
            loaderManager.initLoader(id, null, callbacks);
        }
    }

    @Override
    public boolean isDisposed() {
        return loaderManager.getLoader(id) == null;
    }

    @Override
    public void dispose() {
        loaderManager.destroyLoader(id);
    }
}
