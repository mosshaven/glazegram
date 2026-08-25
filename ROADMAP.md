# Roadmap

## Phase 0 — Project bootstrap
- [x] Android project
- [x] Kotlin
- [x] Jetpack Compose
- [x] TDLib integration
- [x] Project builds and launches

## Phase 1 — Authorization
- [x] TDLib initialization
- [x] Phone number login
- [x] Login code
- [x] 2FA password
- [x] Logout
- [x] Session restore

## Phase 2 — Chat list
- [x] Load chats
- [x] Chat titles
- [x] Avatars
- [x] Last message
- [x] Unread counters
- [x] Pinned chats
- [x] Realtime updates

## Phase 3 — Chat screen
- [x] Open chat
- [x] Load message history
- [x] Receive new messages
- [x] Send text messages
- [ ] Replies (interaction foundation implemented; cross-chat navigation and quotes remain)
- [x] Message status
- [x] Visible-message read acknowledgements
- [x] Sender metadata, avatars and chat actions
- [x] Member/online counts and permission-aware composer

## Phase 3.1 — Functional UI foundation
- [x] Material 3 and Monet colors
- [x] Stable chat-list scroll position
- [x] Navigation drawer with account header
- [x] Settings and confirmed logout
- [x] Message bubbles and composer
- [x] Light/dark system bars
- [x] Functional transition animations

## Phase 3.2 — Chat interaction completeness
- [x] Explicit reply/composer state and TDLib reply mechanism
- [x] Reply target resolution with `GetMessage`
- [x] Around-message loading, exact-ID navigation and highlight
- [x] Extensible message action state with Reply and Copy
- [x] Presentation-only media album grouping with stable identities
- [x] Realtime album regrouping from message/file updates
- [x] Aspect-ratio media layout with minithumbnail placeholders
- [x] Chat ViewModel/state-holder boundary
- [x] Swipe-to-reply and jump-to-bottom unread control
- [x] Forward-origin and basic formatted-text rendering
- [x] Property-based single-message deletion
- [ ] Complete album boundaries split across history pages
- [ ] Cross-chat reply navigation and quote selection
- [ ] Production mosaic geometry and async image decoding

## Phase 4 — Basic messaging
- [ ] Media message models and preview loading (partial; core photo/video metadata supported)
- Photos
- Videos
- Files
- Voice messages
- Stickers
- GIFs
- Reactions
- Edit messages
- [x] Delete single messages when allowed by TDLib

## Phase 5 — Telegram basics
- Search
- Profiles
- Groups
- Channels
- Contacts
- Saved Messages
- Notifications

## Phase 6 — Polish
- Proper error handling
- Loading states
- Offline behavior
- Performance
- Background operation

## Phase 7 — Custom client features
- Themes
- iOS-like UI
- Material UI
- Liquid Glass
- Customization
- Monet Light/Dark/AMOLED presets
- Configurable bubble shapes
- Open-source and system font selection
- Custom emoji font selection
- Folder tab styles and icons
- Message details and extended context actions
- Per-chat and whole-chat translation
- Advanced forward/send options
- Drawer appearance customization
- Media quality and transfer controls
- Press-scale and context-menu animations
- Local aliases
- Notes
- Snooze
- etc.
