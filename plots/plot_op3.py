import matplotlib.pyplot as plt
import pandas as pd

df = pd.read_csv('op3_log.csv').round(2)
phases = df["phase"].unique()
labels = ['Confidence value for average hypotheses', 'Percent correct in blocks of 12 trials']

for p in phases:
    x = df[df["phase"] == p]['count_block_loop']
    y1 = df[df["phase"] == p]['h1_average_c']
    y2 = df[df["phase"] == p]['block_correct']
    line1 = plt.step(x + 1, y1, label=p, alpha=0.6, color='green', linewidth=2.5)
    line3 = plt.plot(x + 1, y2, 'o-', alpha=1.0, color='black')

plt.vlines(3.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.vlines(9.5, 0, 1, linewidth=2, linestyle='--', color='black', alpha=1.0)
plt.grid(axis='x', color='0.95')
plt.xticks([2.0, 6.5, 11.0], ['Baseline', 'Training', 'Testing'])
plt.yticks([0, 0.25, 0.5, 0.75, 1.00])
lns = line1 + line3
plt.legend(lns, labels, framealpha=1.0, loc='lower right')
plt.title('Op3: Matching-to-Sample (fNARS)')
plt.tight_layout()
plt.savefig('figure_op3.pdf')
print("Saved figure_op3.pdf")
