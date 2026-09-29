package com.gl4a.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.gl4a.R;
import com.gl4a.gitlab.model.GitLabGroup;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.MembershipRoles;

/**
 * Binds row_group_tree_item rows for groups and projects, like GitLab web's group lists
 * (#172): GitLab type icon, avatar, name, visibility icon, role badge and description, with
 * the row and its divider indented by depth.
 */
public final class GroupTreeRows {
    private GroupTreeRows() {}

    /** @param expandable show the chevron slot (tree); false for flat lists */
    public static void bind(View row, @Nullable GitLabGroup group, @Nullable GitLabProject project,
            int depth, boolean expandable, @Nullable MembershipRoles roles) {
        Context context = row.getContext();
        int indentPx = Math.round(24 * context.getResources().getDisplayMetrics().density);

        View content = row.findViewById(R.id.row_content);
        content.setPaddingRelative(Math.round(4 * context.getResources().getDisplayMetrics().density)
                + depth * indentPx, content.getPaddingTop(), content.getPaddingEnd(),
                content.getPaddingBottom());
        View divider = row.findViewById(R.id.divider);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) divider.getLayoutParams();
        lp.setMarginStart(depth * indentPx);
        divider.setLayoutParams(lp);

        ImageView expand = row.findViewById(R.id.iv_expand);
        expand.setVisibility(!expandable ? View.GONE : group != null ? View.VISIBLE : View.INVISIBLE);
        expand.setRotation(-90);

        ((ImageView) row.findViewById(R.id.iv_type)).setImageResource(group != null
                ? R.drawable.group_type_subgroup : R.drawable.group_type_project);

        String name = group != null ? group.name : project.name;
        String description = group != null ? group.description : project.description;
        String visibility = group != null ? group.visibility : project.visibility;
        String fullPath = group != null ? group.fullPath : project.pathWithNamespace;

        // Shared logo derivation, same as My repositories (#172).
        ImageView avatar = row.findViewById(R.id.iv_gravatar);
        if (group != null) {
            AvatarHandler.assignGroupLogo(avatar, group);
        } else {
            AvatarHandler.assignProjectLogo(avatar, project);
        }
        ((TextView) row.findViewById(R.id.tv_title)).setText(name);

        ImageView visibilityIcon = row.findViewById(R.id.iv_visibility);
        int visibilityRes = visibilityIcon(visibility);
        visibilityIcon.setVisibility(visibilityRes != 0 ? View.VISIBLE : View.GONE);
        if (visibilityRes != 0) visibilityIcon.setImageResource(visibilityRes);

        TextView role = row.findViewById(R.id.tv_role);
        String roleLabel = roles != null ? roles.labelFor(context, fullPath) : null;
        role.setVisibility(roleLabel != null ? View.VISIBLE : View.GONE);
        role.setText(roleLabel);

        TextView subtitle = row.findViewById(R.id.tv_subtitle);
        subtitle.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);
        subtitle.setText(description);
    }

    /** Same icons as the repo screen (#145): lock, shield, globe. */
    private static int visibilityIcon(@Nullable String visibility) {
        if ("private".equals(visibility)) return R.drawable.private_small;
        if ("internal".equals(visibility)) return R.drawable.internal_small;
        if ("public".equals(visibility)) return R.drawable.public_small;
        return 0;
    }
}
