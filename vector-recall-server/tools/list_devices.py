import argparse

from tools._common import open_db


def main() -> None:
    parser = argparse.ArgumentParser(description="List vector recall device tokens.")
    parser.add_argument("--username", required=True)
    args = parser.parse_args()

    db = open_db()
    for device in db.list_device_tokens(args.username):
        status = "revoked" if device.revoked_at else "active"
        print(
            f"{device.token_prefix}\t{status}\t{device.device_name}\t"
            f"created={device.created_at}\tlast_used={device.last_used_at or ''}"
        )


if __name__ == "__main__":
    main()
