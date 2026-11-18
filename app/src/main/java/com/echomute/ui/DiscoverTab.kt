package com.echomute

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DiscoverTab(
    searchQuery: String,
    searching: Boolean,
    searchResults: List<PodcastSearchResult>,
    feedUrl: String,
    loading: Boolean,
    podcasts: List<Podcast>,
    onSearchQueryChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    onFeedUrlChange: (String) -> Unit,
    onSubscribeFeed: (String) -> Unit,
    onOpenSubscribed: (Podcast) -> Unit
) {
    var showFeedForm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
            Text("Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Find shows to listen ad-free",
                style = MaterialTheme.typography.bodySmall,
                color = UnspliceSlate,
                modifier = Modifier.padding(top = 3.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnspliceSearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = "Search podcasts",
                    imeAction = ImeAction.Search,
                    onSubmit = onRunSearch,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !searching && searchQuery.isNotBlank(),
                    onClick = onRunSearch,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UnspliceMint,
                        contentColor = UnspliceOnMint,
                        disabledContainerColor = UnspliceSurfaceElevated,
                        disabledContentColor = UnspliceMuted
                    ),
                    modifier = Modifier.padding(top = 6.dp).heightIn(min = 54.dp)
                ) {
                    Text(if (searching) "Searching…" else "Search", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            TextButton(
                onClick = { showFeedForm = !showFeedForm },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Filled.RssFeed, contentDescription = null, tint = UnspliceSlate, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add via RSS feed", color = UnspliceIvory, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (showFeedForm) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = UnspliceSlate
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "rss-card") {
                AnimatedVisibility(visible = showFeedForm) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Paste any podcast RSS or Atom address to subscribe directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnspliceSlate
                            )
                            OutlinedTextField(
                                value = feedUrl,
                                onValueChange = onFeedUrlChange,
                                label = { Text("Feed URL") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (!loading && feedUrl.isNotBlank()) onSubscribeFeed(feedUrl) }),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = UnspliceMint,
                                    focusedLabelColor = UnspliceMint,
                                    unfocusedBorderColor = UnspliceOutline,
                                    unfocusedLabelColor = UnspliceSlate,
                                    focusedContainerColor = UnspliceMidnight,
                                    unfocusedContainerColor = UnspliceMidnight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                enabled = !loading && feedUrl.isNotBlank(),
                                onClick = { onSubscribeFeed(feedUrl) },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = UnspliceMint,
                                    contentColor = UnspliceOnMint,
                                    disabledContainerColor = UnspliceSurfaceElevated,
                                    disabledContentColor = UnspliceMuted
                                ),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(if (loading) "Loading…" else "Subscribe", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            if (searchResults.isNotEmpty()) {
                item(key = "results-header") {
                    Text(
                        "Search Results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = UnspliceIvory,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(searchResults, key = { it.feedUrl }) { item ->
                    val subscribed = isPodcastSubscribed(podcasts, item.feedUrl)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastArtwork(item.title, item.artworkUrl, size = 54.dp, cornerRadius = 12.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UnspliceIvory,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.artist.isNotEmpty()) {
                                    Text(
                                        item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UnspliceSlate,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (subscribed) {
                                OutlinedButton(
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.45f)),
                                    onClick = {
                                        val hash = stableHash(item.feedUrl.trim())
                                        podcasts.firstOrNull { it.id == hash || it.feedUrl.equals(item.feedUrl.trim(), ignoreCase = true) }
                                            ?.let(onOpenSubscribed)
                                    }
                                ) {
                                    Text("Subscribed", color = UnspliceMint, maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    enabled = !loading,
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint),
                                    onClick = { onSubscribeFeed(item.feedUrl) }
                                ) {
                                    Text("Subscribe", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            } else if (!showFeedForm && !searching) {
                item(key = "discover-empty") {
                    Spacer(Modifier.height(12.dp))
                    EmptyStateCard(
                        icon = Icons.Filled.Podcasts,
                        title = "Search for any podcast",
                        subtitle = "Look up shows by name or tap Add via RSS feed above to paste a feed link directly."
                    )
                }
            }
        }
    }
}
