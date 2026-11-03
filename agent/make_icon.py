"""Writes remoty.ico (the .exe file icon) from the shared logo. Run by build-exe.bat."""
import remoty_logo

if __name__ == "__main__":
    remoty_logo.render(256).save("remoty.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("remoty.ico written")
