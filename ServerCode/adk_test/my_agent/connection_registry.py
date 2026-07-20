"""
Shared in-memory registry mapping ADK session IDs to their live PC websocket
connections. Kept separate from ADK's session.state because state gets
deep-copied internally, and a live websocket object cannot be deep-copied.
"""

ACTIVE_CONNECTIONS = {}
