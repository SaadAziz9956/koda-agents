import random

WORDS = ["python", "rocket", "galaxy", "keyboard", "quantum", "dragon"]

def hangman():
	word = random.choice(WORDS)
	guessed = set()
	lives = 6

	print("🎮 Hangman! Guess the word. You have 6 lives.\n")

	while lives > 0:
		display = " ".join(c if c in guessed else "_" for c in word)
		print(f"  {display}  |  Lives: {lives}")

		if "_" not in display:
			print("🎉 You win!")
			return

		guess = input("  Guess a letter: ").strip().lower()
		if not guess or len(guess) != 1:
			print("  One letter at a time!\n")
			continue

		if guess in guessed:
			print("  Already guessed that.\n")
			continue

		guessed.add(guess)
		if guess in word:
			print("  ✅ Nice!\n")
		else:
			lives -= 1
			print(f"  ❌ Nope! ({lives} left)\n")

	print(f"💀 Out of lives! The word was: {word}")

if __name__ == "__main__":
	hangman()
