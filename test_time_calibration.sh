#!/bin/bash
# ============================================
# 时间校准测试脚本
# 10 个长度不等的 case，测 HUMANIZE 实际耗时
# 跑完输出 words vs seconds 对照表
#
# 用法: bash test_time_calibration.sh [detect|humanize|both]
# 默认测 humanize
# ============================================

BASE="http://localhost:8080/v1/humanizer"
# 如果需要 auth token，取消注释并填入
# TOKEN="YOUR_CLERK_TOKEN"
# AUTH_HEADER="-H \"Authorization: Bearer $TOKEN\""

MODE="${1:-humanize}"

echo "=============================="
echo "  时间校准测试 (mode=$MODE)"
echo "  $(date)"
echo "=============================="
echo ""

# 基础段落 (~50 words each)
P1="Artificial intelligence has become increasingly prevalent in modern society. The technology continues to evolve at an unprecedented pace, transforming industries and reshaping how we live and work. Many experts predict significant changes ahead."
P2="Machine learning algorithms can now process vast amounts of data in seconds, identifying patterns that would take humans weeks to discover. These capabilities are being applied across healthcare, finance, education, and entertainment sectors with remarkable results."
P3="The ethical implications of widespread AI adoption remain a subject of intense debate among researchers and policymakers. Questions about privacy, bias, accountability, and the future of employment demand careful consideration as these technologies become more powerful."
P4="Natural language processing has made tremendous strides in recent years, enabling machines to understand and generate human language with increasing sophistication. Applications range from automated translation to content creation and sentiment analysis."
P5="Computer vision systems powered by deep learning can now identify objects, faces, and scenes with superhuman accuracy. These systems are being deployed in autonomous vehicles, medical imaging, security surveillance, and quality control applications."
P6="Reinforcement learning has enabled AI agents to master complex games and real-world tasks through trial and error, achieving performance levels that surpass human experts in many domains. This approach shows promise for robotics and optimization."
P7="The development of large language models has opened new frontiers in AI capability, demonstrating emergent abilities in reasoning, coding, and creative writing that were not explicitly programmed. These models continue to grow in size and capability."
P8="Edge computing and AI acceleration hardware are making it possible to run sophisticated models on mobile devices and embedded systems, bringing intelligence closer to where data is generated and decisions need to be made in real time."
P9="Transfer learning has dramatically reduced the data and compute requirements for training AI models on new tasks, making the technology more accessible to organizations with limited resources and enabling rapid deployment across diverse applications."
P10="The intersection of AI and biotechnology promises revolutionary advances in drug discovery, personalized medicine, and genetic engineering. Computational approaches are accelerating research timelines and enabling discoveries that would be impossible through traditional methods alone."

# 构建 10 个不同长度的测试文本
# Case 1: ~50 words (1 段)
# Case 2: ~100 words (2 段)
# Case 3: ~150 words (3 段)
# Case 4: ~250 words (5 段)
# Case 5: ~500 words (10 段)
# Case 6: ~750 words (15 段 = 10+5重复)
# Case 7: ~1000 words (20 段)
# Case 8: ~1500 words (30 段)
# Case 9: ~2000 words (40 段)
# Case 10: ~3000 words (60 段)

build_text() {
  local count=$1
  local result=""
  local paragraphs=("$P1" "$P2" "$P3" "$P4" "$P5" "$P6" "$P7" "$P8" "$P9" "$P10")
  for ((i=0; i<count; i++)); do
    local idx=$((i % 10))
    if [ -n "$result" ]; then
      result="$result ${paragraphs[$idx]}"
    else
      result="${paragraphs[$idx]}"
    fi
  done
  echo "$result"
}

CASE_NAMES=("50w" "100w" "150w" "250w" "500w" "750w" "1000w" "1500w" "2000w" "3000w")
CASE_PARAS=(1 2 3 5 10 15 20 30 40 60)

# 结果存储
declare -a TASK_IDS
declare -a WORD_COUNTS
declare -a CHAR_COUNTS

echo "===== 提交任务 ====="
echo ""

for i in $(seq 0 9); do
  name="${CASE_NAMES[$i]}"
  paras="${CASE_PARAS[$i]}"
  text=$(build_text $paras)
  
  # 统计实际 word 数
  wc=$(echo "$text" | wc -w | tr -d ' ')
  cc=${#text}
  WORD_COUNTS[$i]=$wc
  CHAR_COUNTS[$i]=$cc
  
  # JSON 转义（用 perl，所有 Linux 都有）
  payload=$(perl -e '
    my $text = $ARGV[0];
    $text =~ s/\\/\\\\/g;
    $text =~ s/"/\\"/g;
    $text =~ s/\n/\\n/g;
    print "{\"text\":\"$text\"}";
  ' "$text")
  
  if [ "$MODE" = "detect" ] || [ "$MODE" = "both" ]; then
    endpoint="$BASE/detect"
    type_label="DETECT"
  else
    endpoint="$BASE/process"
    type_label="HUMANIZE"
  fi
  
  echo "[$((i+1))/10] $name: ${wc} words, ${cc} chars → $type_label"
  
  resp=$(curl -s -X POST "$endpoint" \
    -H "Content-Type: application/json" \
    -d "$payload")
  
  # 提取 taskId
  task_id=$(echo "$resp" | perl -ne 'print $1 if /"id"\s*:\s*(\d+)/')
  est=$(echo "$resp" | perl -ne 'print $1 if /"estimatedSeconds"\s*:\s*(\d+)/')
  
  if [ -n "$task_id" ]; then
    TASK_IDS[$i]=$task_id
    echo "  → taskId=$task_id, estimatedSeconds=$est"
  else
    echo "  → ERROR: $resp"
    TASK_IDS[$i]=""
  fi
  
  # 间隔 1 秒避免并发影响
  sleep 1
done

echo ""
echo "===== 所有任务已提交，开始轮询等待完成 ====="
echo ""

# 轮询直到所有任务完成
declare -a ACTUAL_SECONDS
declare -a FINAL_STATUS
MAX_WAIT=600  # 最多等 10 分钟
POLL_INTERVAL=5

elapsed_wait=0
all_done=false

while [ "$all_done" = "false" ] && [ $elapsed_wait -lt $MAX_WAIT ]; do
  all_done=true
  for i in $(seq 0 9); do
    tid="${TASK_IDS[$i]}"
    if [ -z "$tid" ] || [ -n "${ACTUAL_SECONDS[$i]}" ]; then
      continue
    fi
    
    resp=$(curl -s "$BASE/tasks/$tid")
    status=$(echo "$resp" | perl -ne 'print $1 if /"status"\s*:\s*"([^"]+)"/')
    
    if [ "$status" = "COMPLETED" ] || [ "$status" = "FAILED" ]; then
      elapsed_sec=$(echo "$resp" | perl -ne 'print $1 if /"elapsedSeconds"\s*:\s*([\d.]+)/')
      ACTUAL_SECONDS[$i]="${elapsed_sec:-N/A}"
      FINAL_STATUS[$i]="$status"
      echo "  ✓ Case $((i+1)) (${CASE_NAMES[$i]}): $status in ${elapsed_sec}s"
    else
      all_done=false
    fi
  done
  
  if [ "$all_done" = "false" ]; then
    sleep $POLL_INTERVAL
    elapsed_wait=$((elapsed_wait + POLL_INTERVAL))
    # 每 30 秒打印一次等待状态
    if [ $((elapsed_wait % 30)) -eq 0 ]; then
      done_count=0
      for s in "${ACTUAL_SECONDS[@]}"; do [ -n "$s" ] && done_count=$((done_count+1)); done
      echo "  ... 已等待 ${elapsed_wait}s, 完成 ${done_count}/10"
    fi
  fi
done

echo ""
echo "=============================="
echo "  校准结果对照表"
echo "=============================="
echo ""
printf "%-8s | %-8s | %-8s | %-10s | %-10s | %-12s\n" "Case" "Words" "Chars" "Status" "Actual(s)" "Secs/Word"
printf "%-8s-+-%-8s-+-%-8s-+-%-10s-+-%-10s-+-%-12s\n" "--------" "--------" "--------" "----------" "----------" "------------"

for i in $(seq 0 9); do
  name="${CASE_NAMES[$i]}"
  wc="${WORD_COUNTS[$i]}"
  cc="${CHAR_COUNTS[$i]}"
  status="${FINAL_STATUS[$i]:-TIMEOUT}"
  actual="${ACTUAL_SECONDS[$i]:-N/A}"
  
  # 计算 secs/word
  if [ "$actual" != "N/A" ] && [ -n "$actual" ] && [ "$wc" -gt 0 ] 2>/dev/null; then
    spw=$(perl -e "printf('%.4f', $actual / $wc)")
  else
    spw="N/A"
  fi
  
  printf "%-8s | %-8s | %-8s | %-10s | %-10s | %-12s\n" "$name" "$wc" "$cc" "$status" "$actual" "$spw"
done

echo ""
echo "=============================="
echo "  用上面的 Secs/Word 列来校准参数"
echo "  当前 Java 参数:"
echo "    DETECT:   0.12 s/word + 3s overhead"
echo "    HUMANIZE: 0.18 s/word + 5s overhead"
echo ""
echo "  如果实测偏差大，修改 HumanizerApplicationService 中的常量"
echo "=============================="
