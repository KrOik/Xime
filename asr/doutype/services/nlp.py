"""Text intelligence service — entities, hotwords, correction pairs, proofreading."""
from __future__ import annotations

from typing import Any

from ..client import DoubaoIMEClient


class NlpService:
    """Text understanding and lexicon helpers.

    Example::

        from doutype import NlpService
        nlp = NlpService()
        print(nlp.entities("明天飞北京见张总"))
        nlp.set_hotwords(["豆包", "项目代号"])
        print(nlp.proofread("我明天做飞机去北京"))
    """

    def __init__(
        self,
        *,
        device_id: str | None = None,
        client: DoubaoIMEClient | None = None,
    ):
        self.client = client or DoubaoIMEClient(device_id=device_id)

    @property
    def device_id(self) -> str:
        return self.client.device_id

    def entities(self, text: str) -> dict[str, Any]:
        """Extract named entities from text."""
        return self.client.ner(text)

    def set_hotwords(self, words: list[str] | str) -> dict[str, Any]:
        """Upload personal / domain hotwords for recognition bias."""
        return self.client.user_words(words)

    def add_correction(
        self,
        source: str,
        target: str,
        *,
        text: str = "",
        position: int = 0,
    ) -> dict[str, Any]:
        """Register a source→target correction pair."""
        return self.client.modify_pair(source, target, text=text, position=position)

    def proofread(self, text: str) -> dict[str, Any]:
        """Suggest corrections for typos and near-homophones."""
        return self.client.rectify_text(text)

    def format_asr(self, payload: dict[str, Any] | None = None, **kwargs: Any) -> dict[str, Any]:
        """Post-process ASR output formatting helpers."""
        body = dict(payload or {})
        body.update(kwargs)
        if "did" not in body:
            body["did"] = self.device_id
        return self.client.asr_fmt(body)
