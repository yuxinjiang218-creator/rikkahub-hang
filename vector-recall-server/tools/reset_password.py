import argparse

from tools._common import open_db


def main() -> None:
    parser = argparse.ArgumentParser(description="Create or reset a vector recall user password.")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()

    db = open_db()
    db.ensure_user(args.username, args.password)
    print(f"password reset for {args.username}")


if __name__ == "__main__":
    main()
