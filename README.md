# Flippr for RuneLite

A Grand Exchange flipping tool for Old School RuneScape. Flippr suggests what to buy and sell,
keeps a live plan of your eight GE slots, shows you which button to press next, and tracks the profit of
every flip.

It works on its own, and gets smarter when paired with a free account at
[flippr.exchange](https://flippr.exchange).

## Features

- **Suggestions:** what to buy, at what price and quantity, and when to sell, re-price or abort an offer.
- **Plan:** a live board of your GE slots: Pending, Buying, Bought, Selling, with exact listed prices.
- **GE guidance:** highlights the next thing to click (slot, item, price, quantity, Confirm, Collect).
  It only draws on the screen. You do every click yourself.
- **Price and quantity helper:** a "Flippr price / quantity" line in the chatbox, and an optional key
  that fills the suggested number into the GE price or quantity box while that box is open. Nothing is ever
  submitted for you: you always press Enter yourself.
- **Tracker and History:** profit per flip, per item and over time, with tax included.
- **Prices:** search any item for its latest buy and sell prices.
- **Notifications:** offer completed, price alerts, slot free and more. Each one has its own toggle and uses
  RuneLite's own notification settings.

## Free and Premium

Everything for free-to-play items is free. Members (P2P) item suggestions in the Plan come from the
Flippr service and need Flippr Premium. The rest of the plugin (tracking, history, prices, GE guidance
for your own offers) works without it.

## Pairing with flippr.exchange (optional)

1. Sign in at [flippr.exchange](https://flippr.exchange) and open **OSRS → App**.
2. Click **Get pairing code** and enter it in the Flippr sidebar.

Nothing is sent to flippr.exchange until you pair. Once paired, the plugin sends:

- your character name (to match your account),
- your own GE offers and the Plan items you list,
- your completed flips (item, quantities, prices, times), for your Tracker.

Turn **Sync with website** off in the plugin settings to stop sending, or unpair from the sidebar. Item prices
come from the [OSRS Wiki real-time prices API](https://prices.runescape.wiki).

## Local files

The plugin keeps a log of your own flips in `.runelite/flippr` on your computer. It is not uploaded unless
you pair and sync.

## Support

Questions and bug reports: the Flippr Discord (link on [flippr.exchange](https://flippr.exchange)).

## License

BSD 2-Clause. See [LICENSE](LICENSE).
