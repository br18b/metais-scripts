import subprocess
import time

# List of raw reports you want
reports = ["KS", "AS", "ZS", "ISVS", "KRIS", "InfraSluzba", "Projekt", "Program"]

# How many retries per report
MAX_RETRIES = 10
RETRY_DELAY = 0.5  # seconds

for report in reports:
    print(f"Downloading raw report {report}")
    attempt = 1
    while attempt <= MAX_RETRIES:
        try:
            subprocess.run(
                f"run/raw.sh {report}",
                shell=True,
                check=True,
                text=True
            )
            # If success:
            print(f"[OK] {report} downloaded successfully")
            break
        except subprocess.CalledProcessError:
            print(f"[WARN] {report}: attempt {attempt}/{MAX_RETRIES} failed. Retrying in {RETRY_DELAY}s...")
            time.sleep(RETRY_DELAY)
            attempt += 1

    if attempt > MAX_RETRIES:
        print(f"[ERROR] {report}: failed after {MAX_RETRIES} attempts. Moving on...")