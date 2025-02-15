// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

#include "exec/pipeline/crossjoin/cross_join_context.h"

#include <algorithm>
#include <numeric>

#include "common/global_types.h"
#include "exec/pipeline/runtime_filter_types.h"
#include "exec/vectorized/cross_join_node.h"
#include "exprs/expr.h"
#include "fmt/format.h"
#include "runtime/runtime_state.h"
#include "storage/chunk_helper.h"

namespace starrocks::pipeline {

void CrossJoinContext::close(RuntimeState* state) {
    _build_chunks.clear();
}

Status CrossJoinContext::_init_runtime_filter(RuntimeState* state) {
    vectorized::ChunkPtr one_row_chunk = nullptr;
    size_t num_rows = 0;
    for (auto& chunk_ptr : _build_chunks) {
        if (chunk_ptr != nullptr) {
            if (chunk_ptr->num_rows() == 1) {
                one_row_chunk = chunk_ptr;
            }
            num_rows += chunk_ptr->num_rows();
        }
    }
    // build runtime filter for cross join
    if (num_rows == 1) {
        DCHECK(one_row_chunk != nullptr);
        auto* pool = state->obj_pool();
        ASSIGN_OR_RETURN(auto rfs, vectorized::CrossJoinNode::rewrite_runtime_filter(
                                           pool, _rf_descs, one_row_chunk.get(), _conjuncts_ctx));
        _rf_hub->set_collector(_plan_node_id,
                               std::make_unique<RuntimeFilterCollector>(std::move(rfs), RuntimeBloomFilterList{}));
    } else {
        // notify cross join left child
        _rf_hub->set_collector(_plan_node_id, std::make_unique<RuntimeFilterCollector>(RuntimeInFilterList{},
                                                                                       RuntimeBloomFilterList{}));
    }
    return Status::OK();
}

bool CrossJoinContext::finish_probe(int32_t driver_seq, const std::vector<uint8_t>& build_match_flags) {
    std::lock_guard guard(_join_stage_mutex);

    ++_num_post_probers;
    VLOG(3) << fmt::format("CrossJoin operator {} finish probe {}/{}: {}", driver_seq, _num_post_probers,
                           _num_left_probers, fmt::join(build_match_flags, ","));
    bool is_last = _num_post_probers == _num_left_probers;

    // Merge all build_match_flag from all probers
    if (build_match_flags.empty()) {
        return is_last;
    }
    if (_shared_build_match_flag.empty()) {
        _shared_build_match_flag.resize(build_match_flags.size(), 0);
    }
    DCHECK_EQ(build_match_flags.size(), _shared_build_match_flag.size());
    vectorized::ColumnHelper::or_two_filters(&_shared_build_match_flag, build_match_flags.data());

    return is_last;
}

const std::vector<uint8_t> CrossJoinContext::get_shared_build_match_flag() const {
    DCHECK_EQ(_num_post_probers, _num_left_probers) << "all probers should share their states";
    return _shared_build_match_flag;
}

void CrossJoinContext::append_build_chunk(int32_t sinker_id, vectorized::ChunkPtr chunk) {
    _input_chunks[sinker_id].push_back(chunk);
}

int CrossJoinContext::get_build_chunk_start(int index) const {
    DCHECK_LT(index, _build_chunks.size());
    return _build_chunk_desired_size * index;
}

Status CrossJoinContext::finish_one_right_sinker(RuntimeState* state) {
    if (_num_right_sinkers - 1 == _num_finished_right_sinkers.fetch_add(1)) {
        RETURN_IF_ERROR(_init_runtime_filter(state));

        // Accumulate chunks
        ChunkAccumulator accumulator(state->chunk_size());
        for (auto& sink_chunks : _input_chunks) {
            for (auto& tmp_chunk : sink_chunks) {
                if (tmp_chunk && !tmp_chunk->is_empty()) {
                    _num_build_rows += tmp_chunk->num_rows();
                    RETURN_IF_ERROR(accumulator.push(std::move(tmp_chunk)));
                }
            }
        }
        accumulator.finalize();
        while (ChunkPtr output = accumulator.pull()) {
            _build_chunks.emplace_back(std::move(output));
        }
        _input_chunks.clear();
        _input_chunks.shrink_to_fit();

        _build_chunk_desired_size = state->chunk_size();
        _all_right_finished = true;
    }
    return Status::OK();
}

} // namespace starrocks::pipeline