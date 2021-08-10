package de.danoeh.antennapod.core.sync;

import de.danoeh.antennapod.core.R;

public enum SynchronizationProviderViewData {
    NONE("unset", R.string.preference_synchronization_summary_unchoosen, R.drawable.ic_cloud),
    GPODDER_NET("GPodder.net", R.string.gpodnet_description, R.drawable.gpodder_icon),
    NEXTCLOUD_GPODDER("Nextcloud", R.string.preference_synchronization_summary_nextcloud, R.drawable.nextcloud_logo),
    ;

    private final String name;
    private final int iconResource;
    private final int summaryResource;

    SynchronizationProviderViewData(String name, int summaryResource, int iconResource) {
        this.name = name;
        this.iconResource = iconResource;
        this.summaryResource = summaryResource;
    }

    public String getName() {
        return name;
    }

    public int getIconResource() {
        return iconResource;
    }

    public int getSummaryResource() {
        return summaryResource;
    }
}
