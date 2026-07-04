from openai import OpenAI

from config import config


class Embedder:
    def __init__(self):
        self.client = OpenAI(base_url=config.embed_base_url, api_key=config.embed_api_key or "missing")
        self.model = config.embed_model

    def embed(self, text: str) -> list[float]:
        response = self.client.embeddings.create(
            model=self.model,
            input=text[:8000],
        )
        return response.data[0].embedding


embedder = Embedder()
