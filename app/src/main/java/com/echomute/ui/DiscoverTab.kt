package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
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
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Search podcasts or subscribe with an RSS feed link",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
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
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint)
                ) {
                    Text(if (searching) "Searching…" else "Search", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (searchResults.isNotEmpty()) {
                item(key = "results-header") {
                    Text(
                        "Search Results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(searchResults, key = { it.feedUrl }) { item ->
                    val subscribed = isPodcastSubscribed(podcasts, item.feedUrl)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastArtwork(item.title, item.artworkUrl, size = 50.dp, cornerRadius = 10.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.artist.isNotEmpty()) {
                                    Text(
                                        item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (subscribed) {
                                OutlinedButton(
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.5f)),
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
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint),
                                    onClick = { onSubscribeFeed(item.feedUrl) }
                                ) {
                                    Text("Subscribe", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "rss-card") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = UnspliceSurface,
                    border = BorderStroke(1.dp, UnspliceOutline),
                    modifier = Modifier.fillMaxWidth().padding(top = if (searchResults.isNotEmpty()) 6.dp else 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.RssFeed, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(20.dp))
                            Text("Add via RSS feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Paste any podcast RSS or Atom feed URL to subscribe directly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                focusedContainerColor = UnspliceMidnight,
                                unfocusedContainerColor = UnspliceMidnight
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !loading && feedUrl.isNotBlank(),
                            onClick = { onSubscribeFeed(feedUrl) },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(if (loading) "Loading…" else "Subscribe", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
