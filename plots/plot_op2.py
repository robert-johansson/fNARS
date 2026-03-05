import matplotlib.pyplot as plt
import pandas as pd

df = pd.read_csv('op2_log.csv').round(2)
phases = df["phase"].unique()
labels = ['Frequency value of first hypothesis', 'Frequency value of second hypothesis',
          'Percent correct in blocks of 6 trials']

for p in phases:
    x = df[df["phase"] == p]['count_block_loop']
    y1 = df[df["phase"] == p]['h1_average_f']
    y3 = df[df["phase"] == p]['h2_average_f']
    y2 = df[df["phase"] == p]['block_correct']
    line1 = plt.step(x + 1, y1, label=p, alpha=0.6, color='green', linewidth=2.5)
    line2 = plt.step(x + 1, y3, label=p, alpha=0.7, color='blue', linewidth=2.5)
    line3 = plt.plot(x + 1, y2, 'o-', alpha=1.0, color='black')

plt.vlines(2.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.vlines(6.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.vlines(8.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.vlines(12.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.grid(axis='x', color='0.95')
plt.xticks([1.5, 4.5, 7.5, 10.5, 13.5],
           ['Baseline', 'Training 1', 'Testing 1', 'Training 2', 'Testing 2'])
plt.yticks([0, 0.25, 0.5, 0.75, 1.00])
lns = line1 + line2 + line3
plt.legend(lns, labels, framealpha=0.95, loc='lower center', bbox_to_anchor=(0.7, 0.10))
plt.title('Op2: Reversal Learning (fNARS)')
plt.tight_layout()
plt.savefig('figure_op2.pdf')
print("Saved figure_op2.pdf")
