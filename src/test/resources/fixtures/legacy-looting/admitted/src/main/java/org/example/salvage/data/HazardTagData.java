package org.example.salvage.data;

import org.example.salvage.HazardKeys;

public final class HazardTagData {
	public void addTags(TagAppender tags) {
		tag("bypasses_armor").add(HazardKeys.PULVERIZE);
	}

	private TagAppender tag(String name) {
		return null;
	}

	private interface TagAppender {
		void add(Object... keys);
	}
}
