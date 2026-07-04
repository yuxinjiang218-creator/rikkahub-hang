import argparse

from tools._common import now_millis, open_db


def main() -> None:
    parser = argparse.ArgumentParser(description="Revoke all active vector recall device tokens for a user.")
    parser.add_argument("--username", required=True)
    args = parser.parse_args()

    db = open_db()
    count = db.revoke_all_device_tokens(args.username, now_millis())
    print(f"revoked={count}")


if __name__ == "__main__":
    main()
