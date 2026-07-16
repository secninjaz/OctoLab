package com.philosophicalhacker.lib;

import io.reactivex.ObservableEmitter;
import io.reactivex.SingleEmitter;

interface ReactiveEmitter<T> {
    void onError(Throwable error);
    void onLoadFinished(T data);
}
