from openai import OpenAI

from config import config


class Embedder:
    def __init__(self):
        self.client = OpenAI(base_url=config.embed_base_url, api_key=config.embed_api_key or "missing")
        self.model = config.embed_model

    def embed(self, text: str) -> list[float]:
        return self.embed_many([text])[0]

    def embed_many(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        response = self.client.embeddings.create(
            model=self.model,
            input=texts,
        )
        return [item.embedding for item in response.data]


embedder = Embedder()
