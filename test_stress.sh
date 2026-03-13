#!/bin/bash
# ============================================
# Humanizer 极限压测脚本
# 20 个并发长文本，覆盖各种场景
# ============================================

BASE="http://localhost:8080/v1/humanizer"

# 长文本模板（约 2000 字符，每个请求会加上编号使内容不同）
LONG1="The rapid advancement of artificial intelligence has fundamentally transformed numerous aspects of modern society, creating both unprecedented opportunities and significant challenges that demand careful consideration from researchers, policymakers, and the general public alike. From healthcare to education, from finance to entertainment, AI systems are increasingly being deployed to automate tasks, enhance decision-making processes, and create new possibilities that were previously unimaginable. The scope and scale of this transformation continue to accelerate, driven by improvements in computational power, the availability of massive datasets, and breakthroughs in algorithmic design that have enabled machines to perform tasks once thought to be exclusively within the domain of human intelligence. In the field of healthcare, machine learning algorithms are being used to analyze medical images with remarkable accuracy, often surpassing the diagnostic capabilities of experienced physicians who have spent decades honing their skills. These systems can detect subtle patterns in X-rays, MRI scans, CT images, and pathology slides that might escape human observation, potentially leading to earlier detection of diseases such as cancer, cardiovascular conditions, and neurological disorders."

LONG2="The education sector has also witnessed significant transformation through the integration of AI technologies that promise to revolutionize how students learn and how educators teach. Adaptive learning platforms now personalize educational content based on individual student performance, learning styles, and cognitive patterns, creating customized learning pathways that can accommodate diverse needs and abilities. Natural language processing enables automated essay grading and feedback generation, providing students with immediate and detailed responses to their written work while freeing educators to focus on more complex pedagogical tasks. Intelligent tutoring systems provide one-on-one instruction at scale, offering patient and consistent guidance that can supplement traditional classroom teaching. However, these developments also raise important questions about data privacy, algorithmic bias, the digital divide, and the potential displacement of human educators whose expertise and emotional intelligence remain irreplaceable in many educational contexts. The financial industry has embraced AI algorithms that are revolutionizing trading strategies, risk assessment, fraud detection, and customer service in ways that are reshaping the entire landscape of modern finance."

LONG3="Despite these remarkable achievements across virtually every sector of the economy and society, the proliferation of AI technology has also given rise to significant ethical concerns that demand urgent attention and thoughtful resolution. Questions about algorithmic transparency and explainability continue to challenge researchers who struggle to make complex neural network decisions interpretable to human stakeholders. Issues of accountability arise when AI systems make errors or produce harmful outcomes, as existing legal and regulatory frameworks were not designed to address the unique challenges posed by autonomous decision-making systems. Fairness and bias remain critical areas of investigation, as numerous studies have demonstrated that AI systems can perpetuate, amplify, or even create new forms of discrimination based on race, gender, socioeconomic status, and other protected characteristics. The impact of AI on employment and the future of work represents perhaps the most consequential and contentious aspect of the ongoing technological revolution. Environmental applications of artificial intelligence offer both tremendous promise and important cautionary lessons about the complex relationship between technology and sustainability."

echo "============================================"
echo "  Humanizer 极限压测"
echo "  时间: $(date)"
echo "============================================"
echo ""

# ============================================
# 第一波：10 个并发 DETECT 长文本
# ============================================
echo "========== 第一波：10 个并发 DETECT 长文本 =========="
DETECT_IDS=""
for i in $(seq 1 10); do
  (
    RESULT=$(curl -s -X POST "$BASE/detect" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"[Stress DETECT #$i] $LONG1 $LONG2 $LONG3\"}")
    echo "  DETECT #$i: $RESULT"
  ) &
done
wait
echo ""

# ============================================
# 第二波：10 个并发 HUMANIZE 长文本
# ============================================
echo "========== 第二波：10 个并发 HUMANIZE 长文本 =========="
for i in $(seq 1 10); do
  (
    RESULT=$(curl -s -X POST "$BASE/process" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"[Stress HUMANIZE #$i] $LONG1 $LONG2\"}")
    echo "  HUMANIZE #$i: $RESULT"
  ) &
done
wait
echo ""

# ============================================
# 第三波：混合并发（5 DETECT + 5 HUMANIZE 同时）
# ============================================
echo "========== 第三波：混合并发 5 DETECT + 5 HUMANIZE =========="
for i in $(seq 1 5); do
  (
    RESULT=$(curl -s -X POST "$BASE/detect" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"[Mixed DETECT #$i] $LONG1 $LONG3\"}")
    echo "  混合 DETECT #$i: $RESULT"
  ) &
  (
    RESULT=$(curl -s -X POST "$BASE/process" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"[Mixed HUMANIZE #$i] $LONG2 $LONG3\"}")
    echo "  混合 HUMANIZE #$i: $RESULT"
  ) &
done
wait
echo ""

# ============================================
# 边界测试
# ============================================
echo "========== 边界测试 =========="

# 超长文本（接近 60000 字符限制）
echo "--- 超长文本测试（约 55000 字符）---"
HUGE_TEXT=""
for i in $(seq 1 18); do
  HUGE_TEXT="$HUGE_TEXT $LONG1 $LONG2 $LONG3"
done
ESCAPED_HUGE=$(perl -e '
  local $/;
  my $text = $ARGV[0];
  $text =~ s/\\/\\\\/g;
  $text =~ s/"/\\"/g;
  $text =~ s/\n/\\n/g;
  $text =~ s/\r//g;
  print $text;
' "$HUGE_TEXT")
curl -s -X POST "$BASE/detect" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"$ESCAPED_HUGE\"}"
echo ""

# 超出限制的文本（应该报错）
echo "--- 超出 60000 字符限制测试 ---"
OVER_TEXT=""
for i in $(seq 1 25); do
  OVER_TEXT="$OVER_TEXT $LONG1 $LONG2 $LONG3"
done
ESCAPED_OVER=$(perl -e '
  local $/;
  my $text = $ARGV[0];
  $text =~ s/\\/\\\\/g;
  $text =~ s/"/\\"/g;
  $text =~ s/\n/\\n/g;
  $text =~ s/\r//g;
  print $text;
' "$OVER_TEXT")
curl -s -X POST "$BASE/detect" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"$ESCAPED_OVER\"}"
echo ""

# 空文本
echo "--- 空文本测试 ---"
curl -s -X POST "$BASE/detect" -H "Content-Type: application/json" -d '{"text":""}'
echo ""

# 纯空格
echo "--- 纯空格测试 ---"
curl -s -X POST "$BASE/detect" -H "Content-Type: application/json" -d '{"text":"     "}'
echo ""

echo ""
echo "============================================"
echo "  提交完成！共提交约 30+ 个任务"
echo "============================================"
echo ""

# ============================================
# 等待后查看状态
# ============================================
sleep 10

echo "========== 10 秒后任务状态概览 =========="
echo "--- 总任务数 ---"
curl -s "$BASE/tasks?page=1&size=1" | perl -ne 'print "总任务数: $1\n" if /"total":(\d+)/'
echo ""

echo "--- 最新 30 个任务状态 ---"
curl -s "$BASE/tasks?page=1&size=30" | perl -ne '
  while (/"id":(\d+).*?"taskType":"(\w+)".*?"status":"(\w+)"/g) {
    printf "  id=%-4s type=%-10s status=%s\n", $1, $2, $3;
  }
'
echo ""

echo "--- 按状态统计 ---"
curl -s "$BASE/tasks?page=1&size=100" | perl -ne '
  my %counts;
  while (/"status":"(\w+)"/g) { $counts{$1}++ }
  for my $s (sort keys %counts) { printf "  %-12s: %d\n", $s, $counts{$s} }
'
echo ""

echo "============================================"
echo "  后续操作："
echo "  # 每隔 30 秒查一次进度"
echo "  watch -n 30 'curl -s $BASE/tasks?page=1&size=5 | perl -ne \"while (/\\\"id\\\":(\\d+).*?\\\"status\\\":\\\"(\\w+)\\\"/g) { print \\\"id=\\\$1 status=\\\$2\\n\\\" }\"'"
echo ""
echo "  # 查看某个任务详情"
echo "  curl -s $BASE/tasks/21"
echo ""
echo "  # 查看 Java 日志"
echo "  docker logs --tail 50 studyagent-v2-springboot-backend"
echo "============================================"
