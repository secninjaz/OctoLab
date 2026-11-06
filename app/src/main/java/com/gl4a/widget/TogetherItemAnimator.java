package com.gl4a.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.Set;

/**
 * The list's usual animation, except that added rows fade in at the same time as the rest
 * changes, not after it (#316). DefaultItemAnimator starts additions only once removals, moves
 * and changes are done, so in the drawer the one row more of the account list (the 5th, an
 * account or "Add account") appeared after the others.
 */
public class TogetherItemAnimator extends DefaultItemAnimator {
    private final Set<RecyclerView.ViewHolder> mRunningAdds = new HashSet<>();

    @Override
    public boolean animateAdd(RecyclerView.ViewHolder holder) {
        View view = holder.itemView;
        view.animate().cancel();
        view.setAlpha(0f);
        mRunningAdds.add(holder);
        dispatchAddStarting(holder);
        view.animate()
                .alpha(1f)
                .setDuration(getAddDuration())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        view.setAlpha(1f);
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.animate().setListener(null);
                        if (mRunningAdds.remove(holder)) {
                            dispatchAddFinished(holder);
                            if (!isRunning()) {
                                dispatchAnimationsFinished();
                            }
                        }
                    }
                })
                .start();
        // Started already: nothing for runPendingAnimations to delay
        return false;
    }

    @Override
    public void endAnimation(@NonNull RecyclerView.ViewHolder item) {
        if (mRunningAdds.contains(item)) {
            item.itemView.animate().cancel();
        }
        super.endAnimation(item);
    }

    @Override
    public void endAnimations() {
        for (RecyclerView.ViewHolder holder : new HashSet<>(mRunningAdds)) {
            holder.itemView.animate().cancel();
        }
        super.endAnimations();
    }

    @Override
    public boolean isRunning() {
        return !mRunningAdds.isEmpty() || super.isRunning();
    }
}
