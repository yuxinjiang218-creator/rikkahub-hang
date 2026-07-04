import argparse

from tools._common import now_millis, open_db


def main() -> None:
    parser = argparse.ArgumentParser(description="Revoke one vector recall device token by token prefix.")
    parser.add_argument("--token-prefix", required=True)
    args = parser.parse_args()

    db = open_db()
    count = db.revoke_device_token(args.token_prefix, now_millis())
    print(f"revoked={count}")


if __name__ == "__main__":
    main()
